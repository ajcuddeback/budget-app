package com.budgetowl.checkin.service;

import com.budgetowl.bill.domain.OccurrenceStatus;
import com.budgetowl.bill.domain.StatusSource;
import com.budgetowl.checkin.domain.AnswerChoice;
import com.budgetowl.checkin.domain.AnswerShape;
import com.budgetowl.checkin.domain.CheckIn;
import com.budgetowl.checkin.domain.CheckInAnswer;
import com.budgetowl.checkin.domain.CheckInRefusal;
import com.budgetowl.checkin.domain.CheckInRefusal.Stale;
import com.budgetowl.checkin.domain.ItemKeys;
import com.budgetowl.checkin.domain.ItemKind;
import com.budgetowl.checkin.domain.StaleReason;
import com.budgetowl.checkin.persistence.CategoryRow;
import com.budgetowl.checkin.persistence.CheckInAnswerRepository;
import com.budgetowl.checkin.persistence.CheckInLedgerRepository;
import com.budgetowl.checkin.persistence.CheckInRepository;
import com.budgetowl.checkin.persistence.LedgerRow;
import com.budgetowl.checkin.service.CheckInViews.ChargeReason;
import com.budgetowl.checkin.service.Picture.Item;
import com.budgetowl.checkin.service.Picture.Target;
import com.budgetowl.common.ConflictException;
import com.budgetowl.common.DomainException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.Money;
import com.budgetowl.debt.service.NewDebtPayment;
import com.budgetowl.household.domain.Household;
import com.budgetowl.transaction.service.TransactionService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Completes a check-in: <b>everything, in one database transaction, or nothing</b>
 * (docs/features/weekly-check-in.md, "Completing a check-in").
 *
 * <ol>
 *   <li>The caller has already checked the role and locked the check-in row ({@code
 *       CheckInService.complete}).
 *   <li>The transactions the completion will act on are locked, in id order, so nothing can
 *       re-file, attach, pair or edit them between the check and the write.
 *   <li>Everything is re-derived under those locks and the digest compared with the basis the
 *       person saw.
 *   <li>Every answer is re-checked, collecting every failure rather than the first.
 *   <li>Answers are applied <b>through the owning services</b> — never by writing their tables — in
 *       the order the feature doc fixes: step 1's occurrence statuses, step 2's categories then
 *       payee rules, step 3's attaches then charge rules then the debt-plan prompts, step 4's
 *       payment or contribution.
 *   <li>The snapshot is written and the row closed.
 * </ol>
 *
 * <p>Any refusal from an owning service — a goal or a debt at its cap, an occurrence recorded a
 * moment ago — and a deadlock PostgreSQL detects and aborts are reported as {@code 409
 * check-in-stale} for that item, and the whole transaction rolls back.
 *
 * <p><b>It never moves money and never creates, deletes, pairs or splits a transaction.</b> The
 * only transaction write is {@link TransactionService#recategorise}, which {@code
 * CheckInArchitectureTest} holds.
 */
@Service
public class CheckInCompletion {

    private final CheckInPictures pictures;
    private final CheckInLedgerRepository ledger;
    private final CheckInRepository checkIns;
    private final CheckInAnswerRepository answers;
    private final CheckInWriters writers;
    private final Clock clock;

    public CheckInCompletion(
            CheckInPictures pictures,
            CheckInLedgerRepository ledger,
            CheckInRepository checkIns,
            CheckInAnswerRepository answers,
            CheckInWriters writers,
            Clock clock) {
        this.pictures = pictures;
        this.ledger = ledger;
        this.checkIns = checkIns;
        this.answers = answers;
        this.writers = writers;
        this.clock = clock;
    }

    /**
     * @param checkIn the draft, already locked {@code FOR UPDATE} by the caller
     * @param sentBasis what the person last saw
     * @throws CheckInRefusal {@code check-in-stale} with every affected item, or {@code
     *     check-in-incomplete}
     */
    @Transactional
    public void complete(UUID actorId, Household household, CheckIn checkIn, String sentBasis) {
        try {
            completeLocked(actorId, household, checkIn, sentBasis);
        } catch (PessimisticLockingFailureException deadlock) {
            // PostgreSQL detected a deadlock against a concurrent ledger edit and aborted us. A
            // re-read and a retry succeed; nothing was written.
            throw CheckInRefusal.stale(List.of(new Stale(null, StaleReason.BASIS_CHANGED)));
        }
    }

    private void completeLocked(
            UUID actorId, Household household, CheckIn checkIn, String sentBasis) {
        UUID householdId = household.id();
        LocalDate today = LocalDate.now(clock);
        List<CheckInAnswer> stored = answers.findForCheckIn(householdId, checkIn.id());

        // Lock first, then look: every row an answer or a rule's pre-answer will act on.
        Picture first =
                pictures.derive(
                        actorId,
                        household,
                        checkIn.week(),
                        checkIn,
                        checkIn.windowStart(),
                        stored,
                        today);
        Set<UUID> toLock = new TreeSet<>();
        for (CheckInAnswer answer : stored) {
            answer.transactionId().ifPresent(toLock::add);
        }
        for (Item item : first.items().values()) {
            if (item.row() != null && !item.question()) {
                toLock.add(item.row().id());
            }
        }
        if (!toLock.isEmpty()) {
            ledger.lock(householdId, toLock);
        }
        Picture picture =
                pictures.derive(
                        actorId,
                        household,
                        checkIn.week(),
                        checkIn,
                        checkIn.windowStart(),
                        stored,
                        today);

        List<Stale> stale = new ArrayList<>();
        Map<String, CheckInAnswer> byKey = new HashMap<>();
        for (CheckInAnswer answer : stored) {
            byKey.put(answer.itemKey(), answer);
            StaleReason reason = recheck(householdId, answer, picture);
            if (reason != null) {
                stale.add(new Stale(answer.itemKey(), reason));
            }
        }
        if (!picture.basis().equals(sentBasis)) {
            stale.add(new Stale(null, StaleReason.BASIS_CHANGED));
        }
        if (!stale.isEmpty()) {
            throw CheckInRefusal.stale(stale);
        }
        if (picture.offersAllocation() && !byKey.containsKey(ItemKeys.ALLOCATION)) {
            throw CheckInRefusal.incomplete();
        }

        new Application(actorId, checkIn, picture, byKey, today).run();
    }

    /** Why an answer can no longer be applied as given, or null when it still can. */
    private StaleReason recheck(UUID householdId, CheckInAnswer answer, Picture picture) {
        Item item = picture.items().get(answer.itemKey());
        return switch (answer.itemKind()) {
            case OVERDUE_BILL -> recheckOverdue(answer, item, picture);
            case UNUSUAL_CHARGE, CATEGORIZE ->
                    recheckTransaction(householdId, answer, item, picture);
            case BILL_CHARGE -> recheckCharge(householdId, answer, item, picture);
            case ALLOCATION -> recheckAllocation(answer, item, picture);
        };
    }

    private StaleReason recheckOverdue(CheckInAnswer answer, Item item, Picture picture) {
        UUID billId = answer.billId().orElseThrow();
        Target target = new Target(billId, answer.dueDate().orElseThrow());
        if (!picture.activeBills().contains(billId)) {
            return StaleReason.BILL_INACTIVE;
        }
        if (item == null) {
            OccurrenceStatus status = picture.occurrenceStatuses().get(target);
            return status != null && status != OccurrenceStatus.DUE
                    ? StaleReason.ALREADY_RECORDED
                    : StaleReason.SUBJECT_GONE;
        }
        if (!java.util.Objects.equals(
                answer.subjectVersion().orElse(null), item.subjectVersion())) {
            return StaleReason.ALREADY_RECORDED;
        }
        if (answer.recordDebtPayment() && !picture.debtByBill().containsKey(billId)) {
            return StaleReason.DEBT_PAID_OFF;
        }
        return null;
    }

    private StaleReason recheckTransaction(
            UUID householdId, CheckInAnswer answer, Item item, Picture picture) {
        if (item == null) {
            return whyTheRowWentAway(householdId, answer);
        }
        if (!java.util.Objects.equals(
                answer.subjectVersion().orElse(null), item.subjectVersion())) {
            return StaleReason.SUBJECT_CHANGED;
        }
        if (answer.choice() == AnswerChoice.CATEGORY) {
            CategoryRow category = picture.categories().get(answer.categoryId().orElseThrow());
            if (category == null || category.archived()) {
                return StaleReason.TARGET_ARCHIVED;
            }
        }
        if (answer.choice() == AnswerChoice.PREPAID
                && (item.target() == null
                        || !item.target().billId().equals(answer.billId().orElse(null))
                        || !item.target().dueDate().equals(answer.dueDate().orElse(null)))) {
            return picture.activeBills().contains(answer.billId().orElseThrow())
                    ? StaleReason.ALREADY_RECORDED
                    : StaleReason.BILL_INACTIVE;
        }
        return null;
    }

    private StaleReason recheckCharge(
            UUID householdId, CheckInAnswer answer, Item item, Picture picture) {
        if (item == null) {
            return whyTheRowWentAway(householdId, answer);
        }
        if (answer.choice() != AnswerChoice.ATTACH) {
            return null;
        }
        Target target = new Target(answer.billId().orElseThrow(), answer.dueDate().orElseThrow());
        if (!picture.activeBills().contains(target.billId())) {
            return StaleReason.BILL_INACTIVE;
        }
        Set<Target> allowed = picture.attachTargets().getOrDefault(answer.itemKey(), Set.of());
        if (!allowed.contains(target)) {
            OccurrenceStatus status = picture.occurrenceStatuses().get(target);
            return status != null && status != OccurrenceStatus.DUE
                    ? StaleReason.ALREADY_RECORDED
                    : StaleReason.SUBJECT_GONE;
        }
        if (!java.util.Objects.equals(
                answer.subjectVersion().orElse(null), picture.versionOf(target))) {
            return StaleReason.ALREADY_RECORDED;
        }
        if (answer.recordDebtPayment() && !picture.debtByBill().containsKey(target.billId())) {
            return StaleReason.DEBT_PAID_OFF;
        }
        return null;
    }

    private static StaleReason recheckAllocation(CheckInAnswer answer, Item item, Picture picture) {
        if (item == null
                || picture.surplus() == null
                || answer.amount().orElseThrow().compareTo(picture.surplus().amount()) != 0) {
            return StaleReason.SURPLUS_CHANGED;
        }
        return switch (answer.choice()) {
            case DEBT ->
                    picture.offeredDebts().contains(answer.debtId().orElseThrow())
                            ? null
                            : StaleReason.DEBT_PAID_OFF;
            case GOAL ->
                    picture.offeredGoals().contains(answer.goalId().orElseThrow())
                            ? null
                            : StaleReason.GOAL_ARCHIVED;
            default -> null;
        };
    }

    /** The row behind an answer is no longer an item: attached, edited, re-filed or gone. */
    private StaleReason whyTheRowWentAway(UUID householdId, CheckInAnswer answer) {
        List<LedgerRow> rows =
                ledger.rows(householdId, List.of(answer.transactionId().orElseThrow()));
        if (rows.isEmpty()) {
            return StaleReason.SUBJECT_GONE;
        }
        return rows.getFirst().attached()
                ? StaleReason.TRANSACTION_ATTACHED_ELSEWHERE
                : StaleReason.SUBJECT_CHANGED;
    }

    /** One application of a checked draft. */
    private final class Application {

        private final UUID actorId;
        private final CheckIn checkIn;
        private final Picture picture;
        private final Map<String, CheckInAnswer> byKey;
        private final LocalDate today;
        private final List<DebtPrompt> prompts = new ArrayList<>();

        Application(
                UUID actorId,
                CheckIn checkIn,
                Picture picture,
                Map<String, CheckInAnswer> byKey,
                LocalDate today) {
            this.actorId = actorId;
            this.checkIn = checkIn;
            this.picture = picture;
            this.byKey = byKey;
            this.today = today;
        }

        void run() {
            List<Item> items = new ArrayList<>(picture.items().values());
            items.sort(java.util.Comparator.comparing(Item::key));

            // Step 1: occurrence statuses.
            for (Item item : items) {
                CheckInAnswer answer = byKey.get(item.key());
                if (item.kind() == ItemKind.OVERDUE_BILL && answer != null) {
                    applyOverdue(item, answer);
                } else if (item.kind() == ItemKind.UNUSUAL_CHARGE
                        && answer != null
                        && answer.choice() == AnswerChoice.PREPAID) {
                    applyPrepaid(item);
                }
            }
            // Step 2: categories, then payee rules.
            List<CheckInAnswer> preAnswers = new ArrayList<>();
            for (Item item : items) {
                if (item.kind() != ItemKind.CATEGORIZE) {
                    continue;
                }
                CheckInAnswer answer = byKey.get(item.key());
                if (answer != null && answer.choice() == AnswerChoice.CATEGORY) {
                    recategorise(
                            item,
                            answer.categoryId().orElseThrow(),
                            answer.subjectVersion().orElseThrow());
                } else if (answer == null && item.rulePreAnswers()) {
                    recategorise(item, item.ruleCategoryId(), item.row().version());
                    CheckInAnswer recorded =
                            CheckInAnswer.aboutTransaction(
                                    checkIn, ItemKind.CATEGORIZE, item.row().id());
                    recorded.record(
                            AnswerChoice.CATEGORY,
                            AnswerShape.category(item.ruleCategoryId(), false),
                            item.row().version(),
                            actorId);
                    recorded.suppliedByRule();
                    preAnswers.add(recorded);
                }
            }
            for (Item item : items) {
                CheckInAnswer answer = byKey.get(item.key());
                if (item.kind() == ItemKind.CATEGORIZE
                        && answer != null
                        && answer.choice() == AnswerChoice.CATEGORY
                        && answer.remember()) {
                    guarded(
                            item.key(),
                            () ->
                                    writers.payeeRules.remember(
                                            actorId,
                                            item.row().payeeId(),
                                            answer.categoryId().orElseThrow()));
                }
            }
            // Step 3: attaches, then charge rules, then the debt-plan prompts.
            for (Item item : items) {
                if (item.kind() != ItemKind.BILL_CHARGE) {
                    continue;
                }
                CheckInAnswer answer = byKey.get(item.key());
                if (answer != null && answer.choice() == AnswerChoice.ATTACH) {
                    Target target =
                            new Target(
                                    answer.billId().orElseThrow(), answer.dueDate().orElseThrow());
                    UUID occurrence = attach(item, target, StatusSource.MANUAL);
                    if (answer.recordDebtPayment()) {
                        prompts.add(
                                new DebtPrompt(
                                        item.key(),
                                        picture.debtByBill().get(target.billId()),
                                        occurrence,
                                        Money.of(
                                                item.row().amount().negate(),
                                                picture.weekSpending().currency()),
                                        item.row().date()));
                    }
                } else if (answer == null && item.reason() == ChargeReason.RULE_MATCH) {
                    attach(item, item.target(), StatusSource.AUTO_MATCHED);
                    CheckInAnswer recorded =
                            CheckInAnswer.aboutTransaction(
                                    checkIn, ItemKind.BILL_CHARGE, item.row().id());
                    recorded.record(
                            AnswerChoice.ATTACH,
                            AnswerShape.target(
                                    item.target().billId(), item.target().dueDate(), false, false),
                            item.targetVersion(),
                            actorId);
                    recorded.suppliedByRule();
                    preAnswers.add(recorded);
                }
            }
            for (Item item : items) {
                CheckInAnswer answer = byKey.get(item.key());
                if (item.kind() == ItemKind.BILL_CHARGE
                        && answer != null
                        && answer.choice() == AnswerChoice.ATTACH
                        && answer.remember()) {
                    guarded(
                            item.key(),
                            () ->
                                    writers.chargeRules.remember(
                                            actorId,
                                            item.row().payeeId(),
                                            item.row().accountId(),
                                            answer.billId().orElseThrow()));
                }
            }
            for (DebtPrompt prompt : prompts) {
                LocalDate paid = prompt.date().isAfter(today) ? today : prompt.date();
                guarded(
                        prompt.itemKey(),
                        () ->
                                writers.debtPayments.log(
                                        actorId,
                                        prompt.debtId(),
                                        new NewDebtPayment(
                                                paid,
                                                prompt.amount().amount().toPlainString(),
                                                null,
                                                prompt.occurrenceId(),
                                                null,
                                                null)));
            }
            // Step 4: the payment or the contribution.
            CheckInAnswer allocation = byKey.get(ItemKeys.ALLOCATION);
            Money surplus = picture.surplus();
            if (allocation != null) {
                switch (allocation.choice()) {
                    case DEBT ->
                            guarded(
                                    ItemKeys.ALLOCATION,
                                    () ->
                                            writers.debtPayments.logFromCheckIn(
                                                    actorId,
                                                    allocation.debtId().orElseThrow(),
                                                    surplus));
                    case GOAL ->
                            guarded(
                                    ItemKeys.ALLOCATION,
                                    () ->
                                            writers.goals.contributeFromCheckIn(
                                                    actorId,
                                                    allocation.goalId().orElseThrow(),
                                                    surplus));
                    default -> {
                        // Roll over: nothing outside the check-in.
                    }
                }
            }
            for (CheckInAnswer recorded : preAnswers) {
                answers.saveAndFlush(recorded);
            }

            WeeklyFigure.Result figure = picture.figure();
            boolean rolled = allocation != null && allocation.choice() == AnswerChoice.ROLL_OVER;
            Money nextWeek = rolled ? figure.rolledOver() : figure.base();
            checkIn.complete(
                    actorId,
                    Instant.now(clock),
                    picture.weekTarget(),
                    picture.weekSpending(),
                    rolled ? surplus : null,
                    nextWeek,
                    // Read after the payment above, so the snapshot holds the month the week
                    // produced.
                    pictures.debtFreeMonthNow(actorId));
            checkIns.saveAndFlush(checkIn);
        }

        private void applyOverdue(Item item, CheckInAnswer answer) {
            switch (answer.choice()) {
                case MARK_PAID -> {
                    UUID occurrence =
                            settle(
                                    item.key(),
                                    item.target(),
                                    OccurrenceStatus.PAID,
                                    null,
                                    today,
                                    null,
                                    StatusSource.MANUAL);
                    if (answer.recordDebtPayment()) {
                        prompts.add(
                                new DebtPrompt(
                                        item.key(),
                                        picture.debtByBill().get(item.billId()),
                                        occurrence,
                                        item.expected(),
                                        today));
                    }
                }
                case SKIP ->
                        settle(
                                item.key(),
                                item.target(),
                                OccurrenceStatus.SKIPPED,
                                null,
                                null,
                                null,
                                StatusSource.MANUAL);
                default -> {
                    // Leave it: it stays DUE.
                }
            }
        }

        /** Prepaid: this occurrence paid by the charge, and the next k − 1 skipped. */
        private void applyPrepaid(Item item) {
            settle(
                    item.key(),
                    item.target(),
                    OccurrenceStatus.PAID,
                    Money.of(item.row().amount().negate(), picture.weekSpending().currency()),
                    item.row().date(),
                    item.row().id(),
                    StatusSource.MANUAL);
            for (LocalDate skipped : item.skips()) {
                settle(
                        item.key(),
                        new Target(item.target().billId(), skipped),
                        OccurrenceStatus.SKIPPED,
                        null,
                        null,
                        null,
                        StatusSource.MANUAL);
            }
        }

        private UUID attach(Item item, Target target, StatusSource source) {
            return settle(
                    item.key(),
                    target,
                    OccurrenceStatus.PAID,
                    Money.of(item.row().amount().negate(), picture.weekSpending().currency()),
                    item.row().date(),
                    item.row().id(),
                    source);
        }

        private void recategorise(Item item, UUID categoryId, long version) {
            guarded(
                    item.key(),
                    () ->
                            writers.transactions.recategorise(
                                    actorId, item.row().id(), categoryId, version));
        }

        private UUID settle(
                String key,
                Target target,
                OccurrenceStatus status,
                Money actual,
                LocalDate paidDate,
                UUID transactionId,
                StatusSource source) {
            UUID[] id = new UUID[1];
            guarded(
                    key,
                    () ->
                            id[0] =
                                    writers.bills.recordFromCheckIn(
                                            actorId,
                                            target.billId(),
                                            target.dueDate(),
                                            status,
                                            actual,
                                            paidDate,
                                            transactionId,
                                            source));
            return id[0];
        }

        /**
         * Runs one owning-service write, turning its refusal into {@code check-in-stale} for the
         * item. The transaction is already marked for rollback by then; throwing is what rolls
         * every earlier write back with it.
         */
        private void guarded(String key, Runnable write) {
            try {
                write.run();
            } catch (DomainException refused) {
                throw CheckInRefusal.stale(List.of(new Stale(key, reasonFor(refused))));
            }
        }
    }

    private static StaleReason reasonFor(DomainException refused) {
        ErrorCode code = refused.errorCode();
        return switch (code) {
            case GOAL_LIMIT_REACHED, PAYEE_RULE_LIMIT_REACHED, CHARGE_RULE_LIMIT_REACHED ->
                    StaleReason.LIMIT_REACHED;
            case GOAL_ARCHIVED -> StaleReason.GOAL_ARCHIVED;
            case DEBT_PAID_OFF -> StaleReason.DEBT_PAID_OFF;
            case BILL_INACTIVE -> StaleReason.BILL_INACTIVE;
            case STALE_VERSION -> StaleReason.SUBJECT_CHANGED;
            case NOT_FOUND -> StaleReason.SUBJECT_GONE;
            // The debt-payment and occurrence caps answer with the generic code (their codes
            // shipped before this slice and are part of the contract), so the fixed developer
            // detail is what tells a cap from a duplicate. Both texts are this codebase's own.
            case CONFLICT ->
                    refused instanceof ConflictException
                                    && refused.getMessage() != null
                                    && refused.getMessage().contains("the most")
                            ? StaleReason.LIMIT_REACHED
                            : StaleReason.ALREADY_RECORDED;
            default -> StaleReason.SUBJECT_CHANGED;
        };
    }

    /** A debt-plan prompt the person accepted, applied after every attach. */
    private record DebtPrompt(
            String itemKey, UUID debtId, UUID occurrenceId, Money amount, LocalDate date) {

        @Override
        public String toString() {
            return "DebtPrompt[itemKey=" + itemKey + "]";
        }
    }
}
