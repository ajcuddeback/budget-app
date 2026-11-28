package com.budgetowl.debt.service;

import com.budgetowl.common.AmountText;
import com.budgetowl.common.Change;
import com.budgetowl.common.ConflictException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.HouseholdDates;
import com.budgetowl.common.InvalidRequestException;
import com.budgetowl.common.Money;
import com.budgetowl.common.NotFoundException;
import com.budgetowl.debt.domain.Debt;
import com.budgetowl.debt.domain.DebtPayment;
import com.budgetowl.debt.domain.PaymentProvenance;
import com.budgetowl.debt.persistence.DebtPaymentRepository;
import com.budgetowl.debt.persistence.DebtRepository;
import com.budgetowl.debt.persistence.MonthInterestRow;
import com.budgetowl.household.domain.Household;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.service.MembershipService;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payments recorded against a debt — the audit trail its balance is derived from.
 *
 * <p><b>A payment is a plan record, not a transaction</b> (ADR-0035). Logging one writes no ledger
 * row and moves no account balance; it carries a nullable {@code transactionId} naming the real
 * movement when the household knows it, and that link is evidence rather than authority. A
 * household that logs a payment and records no transaction has a correct debt balance and an
 * account balance that is too high — the same gap bills have, for the same reason, and it is
 * visible rather than hidden.
 *
 * <p>Five rules are specific to this service:
 *
 * <ul>
 *   <li><b>Interest is charged once per debt per calendar month</b> (ADR-0043). The first payment
 *       recorded for a debt in a month, by its date, carries {@code round(balance × apr ÷ 100 ÷ 12,
 *       minor unit, HALF_EVEN)}; every further payment dated in that month is all principal. One
 *       rule for every writer — a hand-logged payment, a check-in's bill prompt and its surplus —
 *       because all of them build the row through {@link #draft}, and {@link MonthInterest} is the
 *       only place the decision is taken.
 *   <li><b>Writes to one debt are serialised</b> by a row lock on the debt ({@code DebtLocks}), so
 *       two payments recorded at once cannot both find the month unbooked, nor both take their
 *       split against the same balance.
 *   <li><b>The split is computed once and stored.</b> The principal takes the remainder of the
 *       interest, so {@code interest + principal = amount} exactly and no payment loses or invents
 *       a cent — and a payment in cents splits into two cent figures that each display exactly.
 *   <li><b>A correction keeps the interest.</b> Recomputing it against today's rate would rewrite
 *       history to agree with the present.
 *   <li><b>Overpaying is not clamped.</b> A payment whose principal exceeds the balance takes it
 *       below zero and the screen says the debt is overpaid by that much. Clamping would hide a
 *       real error in somebody's favour.
 *   <li><b>A transaction settles at most one debt payment, ever</b> — {@code
 *       ux_debt_payments_transaction_id}, and ADR-0031's argument about one transaction and one
 *       envelope entry. Counting one charge against two debts would understate both balances
 *       plausibly.
 * </ul>
 *
 * <p>Scoped and role-checked as everywhere: another household's debt or payment is a {@code 404},
 * and a {@code VIEWER} reads the whole history and records nothing.
 */
@Service
public class DebtPaymentService {

    private static final Logger log = LoggerFactory.getLogger(DebtPaymentService.class);

    private final MembershipService memberships;
    private final DebtRepository debts;
    private final DebtPaymentRepository payments;
    private final DebtBalanceService balances;
    private final DebtLinks links;
    private final DebtLocks locks;

    public DebtPaymentService(
            MembershipService memberships,
            DebtRepository debts,
            DebtPaymentRepository payments,
            DebtBalanceService balances,
            DebtLinks links,
            DebtLocks locks) {
        this.memberships = memberships;
        this.debts = debts;
        this.payments = payments;
        this.balances = balances;
        this.links = links;
        this.locks = locks;
    }

    /**
     * One debt's history, newest first, <b>one page at a time</b>.
     *
     * <p>The page envelope every collection returns (docs/guides/api-style.md), and here also a
     * threat-model control: a household with years of records has more payments than one response
     * should carry. Default 50, maximum 200, and a request over the maximum is clamped rather than
     * rejected.
     */
    @Transactional(readOnly = true)
    public DebtPaymentView.Page list(UUID actorId, UUID debtId, int page, int size) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        Household household = membership.household();
        Debt debt = require(debtId, household.id());

        Page<DebtPayment> found =
                payments.findForDebt(household.id(), debt.id(), pageable(page, size));
        List<DebtPaymentView> views = new ArrayList<>(found.getNumberOfElements());
        for (DebtPayment payment : found.getContent()) {
            views.add(view(payment, debt.currency()));
        }
        return new DebtPaymentView.Page(
                List.copyOf(views),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    /**
     * Logs a payment.
     *
     * @throws com.budgetowl.common.ForbiddenException for a {@code VIEWER}
     * @throws ConflictException {@code debt-paid-off}, or {@code conflict} where the transaction
     *     already settles another payment or the debt has the most payments we allow
     * @throws NotFoundException if the debt, the transaction or the occurrence is not this
     *     household's
     */
    @Transactional
    public DebtPaymentView log(UUID actorId, UUID debtId, NewDebtPayment requested) {
        return log(actorId, debtId, requested, null);
    }

    /**
     * Logs a payment whose amount the caller worked out in {@code expected}: refused, before
     * anything is written, unless the debt is in that currency. A check-in's bill prompt carries a
     * base-currency amount, and {@link #log(UUID, UUID, NewDebtPayment)} reads the number in the
     * debt's currency, so without this a non-base debt would be paid the wrong amount without
     * complaint (slice-14 security audit, medium 1).
     *
     * @throws IllegalArgumentException if the debt is not in {@code expected}
     */
    @Transactional
    public DebtPaymentView log(
            UUID actorId, UUID debtId, NewDebtPayment requested, Currency expected) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        Debt debt = lock(debtId, household.id());
        if (expected != null && !expected.equals(debt.currency())) {
            throw new IllegalArgumentException("the amount is not in the debt's currency");
        }
        requireNotSettled(debt);

        requireRoomForAPayment(payments, household.id(), debt.id());

        Currency currency = debt.currency();
        Money amount =
                AmountText.parse(requested.amount(), currency, AmountText.Sign.POSITIVE, "amount");
        LocalDate paidDate = requireNotInTheFuture(requested.paidDate(), "paidDate");

        DebtPayment payment = draft(debt, paidDate, amount, PaymentProvenance.USER_LOGGED).row();
        payment.annotate(requested.note());
        if (requested.transactionId() != null) {
            payment.settledBy(link(requested.transactionId(), household.id(), payment.id()));
        }
        if (requested.billOccurrenceId() != null) {
            payment.recordedFrom(
                    links.requireOccurrence(requested.billOccurrenceId(), household.id()).id());
        }
        if (requested.paidFromAccountId() != null) {
            // Resolved through the household like every other id in the body, so another
            // household's account is a 404 rather than a constraint violation. Nothing is written
            // to the account itself (ADR-0035).
            payment.paidFrom(
                    links.requireAccount(requested.paidFromAccountId(), household.id()).id());
        }

        DebtPayment saved = save(payment);
        settleOrReopen(debt);
        log.info(
                "debt payment logged householdId={} debtId={} paymentId={} byUserId={} linked={}",
                household.id(),
                debtId,
                saved.id(),
                actorId,
                saved.transactionId().isPresent());
        return view(saved, currency);
    }

    /**
     * Records a completed weekly check-in's surplus against a debt, dated today, with provenance
     * {@code CHECK_IN} (docs/features/weekly-check-in.md, decision 5).
     *
     * <p><b>The same path as {@link #log}</b>: the role first, the debt through the household and
     * locked, the paid-off refusal, the shared cap, the same {@link #draft} split and the same
     * settle-or-reopen. Nothing moves at a bank; this is a plan record (ADR-0035).
     *
     * <p><b>No special case for its interest.</b> Slice 14 recorded the surplus principal-only,
     * keyed on this provenance, because the split then charged every payment a month of interest.
     * Under ADR-0043 it follows the general rule: principal only when the month's interest is
     * already booked, and carrying it when the surplus is the month's first payment — in which case
     * the regular payment later that month is all principal, so the month is still charged once.
     *
     * @throws ConflictException {@code debt-paid-off}, or {@code conflict} at the payment cap
     */
    @Transactional
    public DebtPaymentView logFromCheckIn(UUID actorId, UUID debtId, Money amount) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        Debt debt = lock(debtId, household.id());
        requireNotSettled(debt);
        requireRoomForAPayment(payments, household.id(), debt.id());
        if (!amount.currency().equals(debt.currency()) || !amount.isPositive()) {
            throw new IllegalArgumentException("a check-in pays a positive amount in the debt's");
        }
        DebtPayment saved =
                save(draft(debt, balances.today(), amount, PaymentProvenance.CHECK_IN).row());
        settleOrReopen(debt);
        log.info(
                "debt payment recorded from a check-in householdId={} debtId={} paymentId={}"
                        + " byUserId={}",
                household.id(),
                debtId,
                saved.id(),
                actorId);
        return view(saved, debt.currency());
    }

    /**
     * Locks every one of {@code debtIds} that is this household's, in one global order, until the
     * transaction ends.
     *
     * <p>For a transaction that writes to more than one debt — a weekly check-in's completion,
     * which records a bill prompt's payment against one debt and the surplus against another. Each
     * write takes its debt's row lock on its own ({@link #lock}), in whatever order the writes
     * come, and two transactions taking the same two debts in opposite orders deadlock. Taking them
     * all here first, in the order {@link DebtLocks#lockOrder} gives, means every multi-debt writer
     * waits rather than deadlocks. A debt that is not this household's, or no longer exists, is
     * skipped: the write that names it answers for it.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException outside a
     *     transaction, where a lock is released at once and protects nothing
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockInOrder(UUID actorId, Collection<UUID> debtIds) {
        locks.lockInOrder(memberships.requireWriteAccess(actorId).household().id(), debtIds);
    }

    /**
     * What completed check-ins recorded against base-currency debts in a month: the term next
     * week's figure takes off {@code free(M)} (decision 1). A {@code VIEWER} may read it.
     */
    @Transactional(readOnly = true)
    public Money checkInPaymentsIn(UUID actorId, YearMonth period) {
        Household household = memberships.requireMembership(actorId).household();
        Currency base = household.baseCurrency();
        java.math.BigDecimal total =
                payments.checkInPaymentsBetween(
                        household.id(),
                        base.getCurrencyCode(),
                        period.atDay(1),
                        period.atEndOfMonth());
        return total == null ? Money.zero(base) : Money.of(total, base);
    }

    /**
     * Corrects a payment somebody recorded.
     *
     * <p>The stored interest stands and the principal takes the difference — see {@code
     * PaymentChanges}. A household editing its own payment record is not an attack; it is the
     * feature.
     *
     * <p><b>One exception, so a month is never charged twice</b> (ADR-0043): moving a payment that
     * carries interest into another month whose interest a different payment already carries makes
     * it principal only, exactly as a payment logged there would have been. The month it left is
     * then unbooked, and the next payment recorded in that month books it, as after a delete.
     * Moving a principal-only payment never gives it interest: that would be a split taken against
     * a balance from another day.
     */
    @Transactional
    public DebtPaymentView correct(
            UUID actorId, UUID debtId, UUID paymentId, PaymentChanges changes) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        Debt debt = lock(debtId, household.id());
        DebtPayment payment = requirePayment(household.id(), debt.id(), paymentId);
        Currency currency = debt.currency();

        requireCurrentVersion(payment.version(), changes.version());

        if (changes.paidDate() != null) {
            LocalDate moved = requireNotInTheFuture(changes.paidDate(), "paidDate");
            YearMonth into = YearMonth.from(moved);
            boolean leavesItsMonth = !into.equals(YearMonth.from(payment.paidDate()));
            payment.on(moved);
            if (leavesItsMonth
                    && payment.interest().signum() != 0
                    && MonthInterest.isBooked(
                            debt.id(),
                            into,
                            rowsIn(household.id(), debt.id(), into),
                            payment.id())) {
                payment.carryNoInterest(currency);
            }
        }
        if (changes.amount() != null) {
            payment.correctAmount(
                    AmountText.parse(
                            changes.amount(), currency, AmountText.Sign.POSITIVE, "amount"));
        }
        switch (changes.transactionId()) {
            case Change.Unchanged<UUID> ignored -> {
                // Not mentioned.
            }
            case Change.Cleared<UUID> ignored -> payment.settledBy(null);
            case Change.SetTo<UUID> settled ->
                    payment.settledBy(link(settled.value(), household.id(), payment.id()));
        }
        switch (changes.paidFromAccountId()) {
            case Change.Unchanged<UUID> ignored -> {
                // Not mentioned.
            }
            case Change.Cleared<UUID> ignored -> payment.paidFrom(null);
            case Change.SetTo<UUID> from ->
                    payment.paidFrom(links.requireAccount(from.value(), household.id()).id());
        }
        switch (changes.note()) {
            case Change.Unchanged<String> ignored -> {
                // Not mentioned.
            }
            case Change.Cleared<String> ignored -> payment.annotate(null);
            case Change.SetTo<String> written -> payment.annotate(written.value());
        }

        DebtPayment saved = save(payment);
        settleOrReopen(debt);
        log.info(
                "debt payment corrected householdId={} debtId={} paymentId={} byUserId={}",
                household.id(),
                debtId,
                paymentId,
                actorId);
        return view(saved, currency);
    }

    /**
     * Removes a payment.
     *
     * <p>The balance moves back, because it was derived from this row and no other. Nothing in the
     * ledger is touched: a payment that named a transaction was pointing at it, not owning it.
     *
     * <p><b>Deleting the payment that carried a month's interest un-books that month</b>
     * (ADR-0043). The other payments in it keep their stored principal-only splits — history is not
     * rewritten — and the next payment recorded in that month carries the interest again. If none
     * is, the month stands uncharged until a statement says otherwise, and the confirmation's
     * correction row is where the difference shows. It is never charged twice.
     */
    @Transactional
    public void delete(UUID actorId, UUID debtId, UUID paymentId) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        Debt debt = lock(debtId, householdId);
        payments.delete(requirePayment(householdId, debt.id(), paymentId));
        settleOrReopen(debt);
        log.info(
                "debt payment deleted householdId={} debtId={} paymentId={} byUserId={}",
                householdId,
                debtId,
                paymentId,
                actorId);
    }

    /**
     * What logging this payment would record, <b>without recording it</b>.
     *
     * <p>The log-a-payment sheet shows the split and the resulting balance before the household
     * commits (docs/features/debt-plan.md, Payment actions). The alternative was computing it in
     * the browser, which means a second implementation of {@code round(balance × apr ÷ 1200)} — the
     * one formula this whole feature turns on, in the one place it could drift from this one. A few
     * cents of disagreement there, compounded, is a payoff date a month out.
     *
     * <p><b>It cannot disagree with {@link #log}</b>, and that is by construction rather than by
     * testing: both build the row through {@link #draft}. {@code log} saves it; this reads four
     * figures off it and lets it go. {@code
     * DebtIT.the_preview_is_exactly_what_logging_then_records} also asserts the two agree, so that
     * a future edit routing one of them around {@code draft} fails rather than drifts.
     *
     * <p><b>Write access, although it writes nothing.</b> It is served as a {@code POST}, to keep
     * the amount out of URLs that every layer logs, and this repository treats a non-{@code GET} as
     * a write a {@code VIEWER} is refused. It exists only to serve the log-a-payment sheet, which a
     * {@code VIEWER} never sees, so least privilege costs nobody anything. The split is taken
     * against today's balance whatever date the payment will carry, exactly as {@code log} does (a
     * known gap, recorded in the feature doc). <b>Whether it carries a month's interest is decided
     * for {@code paidDate}'s month</b>, today's when none is given, by the same rule logging uses.
     *
     * @throws com.budgetowl.common.ForbiddenException for a {@code VIEWER}
     * @throws NotFoundException if the debt is not this household's
     * @throws ConflictException {@code debt-paid-off}, as logging would be
     */
    @Transactional(readOnly = true)
    public PaymentPreview preview(UUID actorId, UUID debtId, String amountText) {
        return preview(actorId, debtId, amountText, null);
    }

    /**
     * The same, for a payment dated {@code paidDate} — today when null.
     *
     * @throws InvalidRequestException for a date in the future, as logging would be
     */
    @Transactional(readOnly = true)
    public PaymentPreview preview(
            UUID actorId, UUID debtId, String amountText, LocalDate paidDate) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Debt debt = require(debtId, membership.household().id());
        requireNotSettled(debt);

        Currency currency = debt.currency();
        Money amount = AmountText.parse(amountText, currency, AmountText.Sign.POSITIVE, "amount");
        LocalDate date =
                paidDate == null ? balances.today() : requireNotInTheFuture(paidDate, "paidDate");
        return previewOf(draft(debt, date, amount, PaymentProvenance.USER_LOGGED), currency);
    }

    /**
     * What a check-in payment of {@code amount} today would record, built by {@link #draft} and
     * discarded: the weekly check-in's debt choice, which every member reads. <b>Membership, not
     * write access</b>, unlike {@link #preview}: the amount is the server's own surplus, never a
     * figure a caller supplied, so a {@code VIEWER} reading the week learns nothing they could not
     * already read on the debt's page.
     *
     * <p>The split is the general one (ADR-0043): when the surplus would be the month's first
     * payment it carries the month's interest, and the preview says so, so the plan's projection
     * after it can leave that month's interest out rather than charge it a second time.
     */
    @Transactional(readOnly = true)
    public PaymentPreview previewCheckInPayment(UUID actorId, UUID debtId, Money amount) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        Debt debt = require(debtId, membership.household().id());
        return previewOf(
                draft(debt, balances.today(), amount, PaymentProvenance.CHECK_IN), debt.currency());
    }

    // ------------------------------------------------------------------------------- internals

    /**
     * The row logging {@code amount} against this debt would write — built, never saved — and why
     * it carries the interest it does.
     *
     * <p>The single place a payment's split is decided, for every writer: {@link #log} (by hand,
     * and the check-in's bill prompt), {@link #logFromCheckIn}, and both previews. {@link
     * MonthInterest#decide} takes the decision from the debt's stored payments in {@code
     * paidDate}'s month, so the figure a household is shown before it commits is the figure it gets
     * after. A writer holds the debt's row lock before calling this.
     */
    private Drafted draft(
            Debt debt, LocalDate paidDate, Money amount, PaymentProvenance provenance) {
        DebtFacts facts = balances.factsFor(debt);
        Money balanceBefore = facts.balance();
        MonthInterest.Decision decision =
                MonthInterest.decide(
                        debt.id(),
                        paidDate,
                        balanceBefore,
                        facts.apr(),
                        rowsIn(debt.householdId(), debt.id(), YearMonth.from(paidDate)),
                        null,
                        balances.solver());
        return new Drafted(
                DebtPayment.of(
                        debt, paidDate, amount, decision.interest(), balanceBefore, provenance),
                decision);
    }

    /** A drafted row and the interest decision it was built from. */
    private record Drafted(DebtPayment row, MonthInterest.Decision decision) {}

    private static PaymentPreview previewOf(Drafted drafted, Currency currency) {
        DebtPayment unsaved = drafted.row();
        return new PaymentPreview(
                Money.of(unsaved.interest(), currency),
                Money.of(unsaved.principal(), currency),
                Money.of(unsaved.balanceBefore(), currency),
                Money.of(unsaved.balanceAfter(), currency),
                drafted.decision().charge(),
                drafted.decision().month());
    }

    private List<MonthInterestRow> rowsIn(UUID householdId, UUID debtId, YearMonth month) {
        return payments.monthInterestRows(
                householdId, debtId, month.atDay(1), month.atEndOfMonth());
    }

    /**
     * Keeps {@code paid_off_at} in step with the balance the payments now imply.
     *
     * <p>Run after every payment write, including a delete: a debt is settled when its derived
     * balance reaches zero and reopened when a correction puts a balance back on it. Storing the
     * flag rather than deriving it is deliberate — it is what the ladder and the "writes are
     * refused" rule read, and it records <em>when</em> the household got there.
     */
    private void settleOrReopen(Debt debt) {
        Money balance = balances.factsFor(debt).balance();
        boolean settled = debt.isPaidOff();
        if (!balance.isPositive() && !settled) {
            debt.settle(balances.now());
            debts.saveAndFlush(debt);
        } else if (balance.isPositive() && settled) {
            debt.reopen();
            debts.saveAndFlush(debt);
        }
    }

    /**
     * Resolves the transaction a payment points at, and refuses one that already settles another.
     *
     * <p>Resolved through the household first — the same path a plain transaction read uses, and
     * never taken on trust because the debt happens to reference the account. The uniqueness
     * look-up is the readable answer; {@code ux_debt_payments_transaction_id} is the control.
     */
    private UUID link(UUID transactionId, UUID householdId, UUID paymentId) {
        UUID resolved = links.requireTransaction(transactionId, householdId);
        UUID except = paymentId == null ? new UUID(0L, 0L) : paymentId;
        if (payments.countOtherPaymentsSettledBy(householdId, resolved, except) > 0) {
            throw new ConflictException(
                    ErrorCode.CONFLICT, "this transaction already settles a debt payment");
        }
        return resolved;
    }

    /**
     * Refuses a write that would take a debt past {@link DebtService#MAXIMUM_PAYMENTS_PER_DEBT}.
     *
     * <p><b>Static, and shared, because there is more than one writer.</b> The cap lived inside
     * {@link #log} alone while {@code DebtService.confirmBalance} also inserts a {@code
     * DebtPayment} — a {@code STATEMENT_CORRECTION} — so a caller with write access could walk
     * figures through {@code POST /api/debts/&#123;id&#125;/balance} and add a row per request
     * forever, past the cap and past the point logging a payment is refused. The constant's own
     * javadoc asserted that could not happen.
     *
     * <p>It is worse than disk: {@code DebtBalanceService.factsFor} aggregates over every payment
     * in the household on <em>every</em> debt read, so an unbounded history is an unbounded read
     * cost on every screen.
     *
     * <p>Here rather than in each writer so the next code path that inserts a payment cannot
     * quietly omit it.
     */
    static void requireRoomForAPayment(
            DebtPaymentRepository payments, UUID householdId, UUID debtId) {
        if (payments.countByHouseholdIdAndDebtId(householdId, debtId)
                >= DebtService.MAXIMUM_PAYMENTS_PER_DEBT) {
            throw new ConflictException(
                    ErrorCode.CONFLICT, "this debt already has the most payments we allow");
        }
    }

    private static DebtPaymentView view(DebtPayment payment, Currency currency) {
        return new DebtPaymentView(
                payment.id(),
                payment.debtId(),
                payment.paidDate(),
                Money.of(payment.amount(), currency),
                Money.of(payment.interest(), currency),
                Money.of(payment.principal(), currency),
                Money.of(payment.balanceAfter(), currency),
                payment.provenance().name(),
                payment.transactionId().orElse(null),
                payment.billOccurrenceId().orElse(null),
                payment.paidFromAccountId().orElse(null),
                payment.note().orElse(null),
                payment.version());
    }

    private static Pageable pageable(int page, int size) {
        int clamped =
                Math.clamp(
                        size <= 0 ? com.budgetowl.common.web.PageResponse.DEFAULT_SIZE : size,
                        1,
                        com.budgetowl.common.web.PageResponse.MAXIMUM_SIZE);
        return PageRequest.of(Math.max(page, 0), clamped);
    }

    private Debt require(UUID debtId, UUID householdId) {
        return debts.findByIdAndHouseholdId(debtId, householdId)
                .orElseThrow(() -> new NotFoundException("no such debt"));
    }

    /** Resolved through the household, locked until the transaction ends and read fresh. */
    private Debt lock(UUID debtId, UUID householdId) {
        return locks.lock(debtId, householdId);
    }

    private DebtPayment requirePayment(UUID householdId, UUID debtId, UUID paymentId) {
        return payments.findOne(householdId, debtId, paymentId)
                .orElseThrow(() -> new NotFoundException("no such payment"));
    }

    /**
     * A payment's date: see {@link HouseholdDates}. Not before 1900, and not after today anywhere
     * on Earth, so a household east of UTC can date a payment with its own today, and the month
     * whose interest it carries is the household's month.
     */
    private LocalDate requireNotInTheFuture(LocalDate date, String field) {
        return HouseholdDates.requirePlausible(date, field, balances.now());
    }

    /** See {@code AccountService.requireCurrentVersion} — the clear message, not the control. */
    private static void requireCurrentVersion(long actual, long expected) {
        if (actual != expected) {
            throw new ConflictException(
                    ErrorCode.STALE_VERSION, "the payment changed since it was read");
        }
    }

    private static void requireNotSettled(Debt debt) {
        if (debt.isPaidOff()) {
            throw new ConflictException(
                    ErrorCode.DEBT_PAID_OFF,
                    "this debt is paid off; confirm a balance to reopen it");
        }
    }

    /**
     * Saves and flushes, turning the two uniqueness indexes into answers a person can read.
     *
     * <p>The look-up in {@link #link} is the good path; these are the controls. Two members
     * attaching the same charge at the same moment both find nothing — and one of them still has to
     * be told, and told that it is already recorded rather than that something broke.
     */
    private DebtPayment save(DebtPayment payment) {
        try {
            return payments.saveAndFlush(payment);
        } catch (DataIntegrityViolationException violation) {
            if (mentions(violation, "ux_debt_payments_transaction_id")) {
                throw new ConflictException(
                        ErrorCode.CONFLICT, "this transaction already settles a debt payment");
            }
            if (mentions(violation, "ux_debt_payments_bill_occurrence_id")) {
                throw new ConflictException(
                        ErrorCode.CONFLICT, "this bill occurrence already has a payment");
            }
            throw violation;
        }
    }

    /** See {@code BillService.mentions} — matched on our own constraint name, not on text. */
    private static boolean mentions(DataIntegrityViolationException violation, String constraint) {
        for (Throwable cause = violation; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains(constraint)) {
                return true;
            }
        }
        return false;
    }
}
