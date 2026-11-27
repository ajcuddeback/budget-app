package com.budgetowl.checkin.service;

import com.budgetowl.bill.domain.OccurrenceStatus;
import com.budgetowl.bill.service.BillView;
import com.budgetowl.bill.service.BillsForPeriod;
import com.budgetowl.bill.service.OccurrenceView;
import com.budgetowl.checkin.domain.AnswerChoice;
import com.budgetowl.checkin.domain.ChargeRule;
import com.budgetowl.checkin.domain.CheckIn;
import com.budgetowl.checkin.domain.CheckInAnswer;
import com.budgetowl.checkin.domain.IsoWeek;
import com.budgetowl.checkin.domain.ItemKeys;
import com.budgetowl.checkin.domain.ItemKind;
import com.budgetowl.checkin.domain.PayeeRule;
import com.budgetowl.checkin.persistence.CategoryRow;
import com.budgetowl.checkin.persistence.ChargeRuleRepository;
import com.budgetowl.checkin.persistence.CheckInLedgerRepository;
import com.budgetowl.checkin.persistence.CheckInRepository;
import com.budgetowl.checkin.persistence.EnvelopeCategory;
import com.budgetowl.checkin.persistence.HistoryRow;
import com.budgetowl.checkin.persistence.LedgerRow;
import com.budgetowl.checkin.persistence.PayeeRuleRepository;
import com.budgetowl.checkin.persistence.SpendingGroup;
import com.budgetowl.checkin.service.CheckInViews.AnswerView;
import com.budgetowl.checkin.service.CheckInViews.BillChoice;
import com.budgetowl.checkin.service.CheckInViews.ChargeReason;
import com.budgetowl.checkin.service.CheckInViews.ChoiceView;
import com.budgetowl.checkin.service.CheckInViews.DebtPrompt;
import com.budgetowl.checkin.service.CheckInViews.DueLine;
import com.budgetowl.checkin.service.CheckInViews.FiguresView;
import com.budgetowl.checkin.service.CheckInViews.IncomeLine;
import com.budgetowl.checkin.service.CheckInViews.ItemView;
import com.budgetowl.checkin.service.CheckInViews.OccurrenceLine;
import com.budgetowl.checkin.service.CheckInViews.Person;
import com.budgetowl.checkin.service.CheckInViews.PreAnswer;
import com.budgetowl.checkin.service.CheckInViews.RuleCounts;
import com.budgetowl.checkin.service.CheckInViews.Step;
import com.budgetowl.checkin.service.CheckInViews.StepView;
import com.budgetowl.checkin.service.CheckInViews.StreakView;
import com.budgetowl.checkin.service.CheckInViews.WeekView;
import com.budgetowl.checkin.service.Picture.Item;
import com.budgetowl.checkin.service.Picture.Target;
import com.budgetowl.common.Money;
import com.budgetowl.common.schedule.ScheduleKind;
import com.budgetowl.debt.service.DebtView;
import com.budgetowl.goal.service.GoalViews.GoalView;
import com.budgetowl.household.domain.Household;
import com.budgetowl.income.service.IncomeView;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Derives a week: every question, every rule's pre-answer, and every step 4 figure, from the ledger
 * as it stands and the draft's answers. <b>Writes nothing</b>, so a {@code GET} has no side effects
 * (api-style.md) and completion can call it again under its locks to see whether anything moved
 * (decision 9).
 *
 * <p><b>Bounded throughout</b> (threat model, attack 11): the window is at most 28 days, each step
 * lists at most {@link #ITEMS_PER_STEP} items, payee history is at most twelve months for at most
 * the payees in the window and at most {@link #HISTORY_ROWS} rows, and every match is an index
 * lookup by key — nothing pairs rows against rows (the slice-12 lesson).
 */
@Service
public class CheckInPictures {

    /** At most this many items per step: the API's page maximum. */
    public static final int ITEMS_PER_STEP = 200;

    /** Earlier outflows read for the median and the recurring months. */
    public static final int HISTORY_ROWS = 10_000;

    /** Deposits read for the income lines. */
    static final int INFLOW_ROWS = 500;

    /** How far back the streak looks: a year of weeks. */
    static final int STREAK_WEEKS = 53;

    private final CheckInSources sources;
    private final CheckInLedgerRepository ledger;
    private final CheckInRepository checkIns;
    private final PayeeRuleRepository payeeRules;
    private final ChargeRuleRepository chargeRules;

    public CheckInPictures(
            CheckInSources sources,
            CheckInLedgerRepository ledger,
            CheckInRepository checkIns,
            PayeeRuleRepository payeeRules,
            ChargeRuleRepository chargeRules) {
        this.sources = sources;
        this.ledger = ledger;
        this.checkIns = checkIns;
        this.payeeRules = payeeRules;
        this.chargeRules = chargeRules;
    }

    /**
     * The first day a check-in for {@code week} would look at: the day after the last completed
     * check-in's Sunday, never more than 28 days back and never after the Monday (decision 8).
     */
    @Transactional(readOnly = true)
    public LocalDate windowStartFor(UUID householdId, IsoWeek week) {
        LocalDate monday = week.monday();
        LocalDate earliest = monday.minusDays(21);
        List<CheckIn> last =
                checkIns.findCompletedBefore(householdId, monday, PageRequest.of(0, 1));
        if (last.isEmpty()) {
            return earliest;
        }
        LocalDate after = last.getFirst().week().sunday().plusDays(1);
        if (after.isBefore(earliest)) {
            return earliest;
        }
        return after.isAfter(monday) ? monday : after;
    }

    /** The plan's debt-free month as it stands, for a completion's snapshot. */
    @Transactional(readOnly = true)
    public YearMonth debtFreeMonthNow(UUID actorId) {
        return sources.debtFreeMonth(actorId).orElse(null);
    }

    /**
     * @param checkIn the draft, or null for a week nobody has started
     * @param windowStart the draft's, or {@link #windowStartFor} for a week not started
     * @param answers the draft's answers; empty for a week not started
     */
    @Transactional(readOnly = true)
    public Picture derive(
            UUID actorId,
            Household household,
            IsoWeek week,
            CheckIn checkIn,
            LocalDate windowStart,
            List<CheckInAnswer> answers,
            LocalDate today) {
        return new Derivation(actorId, household, week, checkIn, windowStart, answers, today).run();
    }

    /** One derivation's working state. Not shared, not reused. */
    private final class Derivation {

        private final UUID actorId;
        private final UUID householdId;
        private final IsoWeek week;
        private final CheckIn checkIn;
        private final LocalDate from;
        private final LocalDate to;
        private final LocalDate today;
        private final Currency base;
        private final String code;
        private final YearMonth endMonth;
        private final Map<String, CheckInAnswer> answers = new HashMap<>();

        private final Map<UUID, CategoryRow> categories = new HashMap<>();
        private final Map<YearMonth, BillsForPeriod> billsByMonth = new HashMap<>();
        private final Map<UUID, BillView> billById = new HashMap<>();
        private final Map<Target, OccurrenceView> occurrences = new HashMap<>();
        private final Map<UUID, DebtView> debtByBill = new HashMap<>();
        private final Map<Target, String> claimed = new HashMap<>();

        /** Occurrences a derived item already targets, by the item that targets them. */
        private final Map<Target, String> consumed = new HashMap<>();

        private final Fingerprint fingerprint = new Fingerprint();

        private final List<Item> overdue = new ArrayList<>();
        private final List<Item> unusual = new ArrayList<>();
        private final List<Item> categorize = new ArrayList<>();
        private final List<Item> charges = new ArrayList<>();
        private final Map<String, Set<Target>> attachTargets = new HashMap<>();

        Derivation(
                UUID actorId,
                Household household,
                IsoWeek week,
                CheckIn checkIn,
                LocalDate windowStart,
                List<CheckInAnswer> answers,
                LocalDate today) {
            this.actorId = actorId;
            this.householdId = household.id();
            this.week = week;
            this.checkIn = checkIn;
            this.from = windowStart;
            this.to = week.sunday();
            this.today = today;
            this.base = household.baseCurrency();
            this.code = base.getCurrencyCode();
            this.endMonth = YearMonth.from(to);
            for (CheckInAnswer answer : answers) {
                this.answers.put(answer.itemKey(), answer);
            }
        }

        Picture run() {
            for (CategoryRow category : ledger.categories(householdId)) {
                categories.put(category.id(), category);
            }
            loadBills();
            for (DebtView debt : sources.debts(actorId)) {
                // Only a debt in the base currency: the prompt's amount is the bill's, and bills
                // are always in the base currency. A debt in another currency would read that
                // number as its own currency (slice-14 security audit, medium 1), so it is never
                // offered, and an old draft naming one completes as debt-paid-off.
                if (debt.billId() != null
                        && debt.paidOffAt() == null
                        && !debt.archived()
                        && code.equals(debt.currency())) {
                    debtByBill.put(debt.billId(), debt);
                }
            }
            claimAnsweredTargets();

            fingerprint.add("week", week, from, to, code);
            List<LedgerRow> outflows =
                    ledger.unattachedOutflows(
                            householdId, code, from, to, PageRequest.of(0, ITEMS_PER_STEP));
            deriveCharges(outflows);
            deriveOverdue();
            long uncategorisedTotal = ledger.countUncategorised(householdId, code, from, to);
            deriveCategorize(
                    ledger.uncategorised(
                            householdId, code, from, to, PageRequest.of(0, ITEMS_PER_STEP)));
            long more = Math.max(0, uncategorisedTotal - categorize.size());
            fingerprint.add("more", more);
            List<IncomeLine> income = incomeLines();
            long otherCurrency = ledger.countInOtherCurrencies(householdId, code, from, to);
            fingerprint.add("other", otherCurrency);

            Figures figures = figures();
            Map<String, Item> all = new LinkedHashMap<>();
            for (List<Item> step : List.of(overdue, unusual, categorize, charges)) {
                for (Item item : step) {
                    all.put(item.key(), item);
                }
            }
            if (figures.allocation != null) {
                all.put(ItemKeys.ALLOCATION, figures.allocation);
            }
            String basis = fingerprint.digest();

            Map<UUID, String> names = sources.memberNames(actorId);
            List<StepView> steps = new ArrayList<>();
            List<Item> whatsNew = new ArrayList<>(overdue);
            whatsNew.addAll(unusual);
            boolean incomeMissing = income.stream().anyMatch(line -> line.transactionId() == null);
            if (hasQuestion(whatsNew) || incomeMissing) {
                steps.add(step(Step.WHATS_NEW, whatsNew, names, income, null, null));
            }
            if (hasQuestion(categorize)) {
                steps.add(
                        step(Step.CATEGORIZE, categorize, names, null, more, figures.weekSpending));
            }
            if (hasQuestion(charges)) {
                steps.add(billsStep(names));
            }
            steps.add(nextWeekStep(figures, names));

            int byRuleCategorized = 0;
            for (Item item : categorize) {
                if (!item.question()) {
                    byRuleCategorized++;
                }
            }
            int byRuleCharges = 0;
            for (Item item : charges) {
                if (!item.question()) {
                    byRuleCharges++;
                }
            }

            WeekView view =
                    new WeekView(
                            week,
                            from,
                            checkIn == null ? "NOT_STARTED" : checkIn.status().name(),
                            checkIn == null ? null : checkIn.version(),
                            basis,
                            checkIn == null
                                    ? null
                                    : person(checkIn.startedBy().orElse(null), names),
                            null,
                            null,
                            code,
                            otherCurrency,
                            new RuleCounts(byRuleCategorized, byRuleCharges),
                            steps,
                            null,
                            List.of());
            return new Picture(
                    all,
                    figures.weekTarget,
                    figures.weekSpending,
                    figures.surplus,
                    figures.result,
                    figures.offeredDebts,
                    figures.offeredGoals,
                    attachTargets,
                    figures.debtFreeMonth,
                    debtIds(),
                    activeBillIds(),
                    statuses(),
                    versions(),
                    categories,
                    basis,
                    view);
        }

        private Set<UUID> activeBillIds() {
            Set<UUID> ids = new HashSet<>();
            for (BillView bill : billById.values()) {
                if (bill.active()) {
                    ids.add(bill.id());
                }
            }
            return ids;
        }

        private Map<Target, OccurrenceStatus> statuses() {
            Map<Target, OccurrenceStatus> statuses = new HashMap<>();
            for (Map.Entry<Target, OccurrenceView> entry : occurrences.entrySet()) {
                statuses.put(entry.getKey(), entry.getValue().status());
            }
            return statuses;
        }

        private Map<Target, Long> versions() {
            Map<Target, Long> versions = new HashMap<>();
            for (Map.Entry<Target, OccurrenceView> entry : occurrences.entrySet()) {
                if (entry.getValue().version() != null) {
                    versions.put(entry.getKey(), entry.getValue().version());
                }
            }
            return versions;
        }

        // ------------------------------------------------------------------------- reference

        /**
         * The months the window, the week's spending and next week touch: at most three, each one
         * {@code BillsForPeriod} read.
         */
        private void loadBills() {
            Set<YearMonth> months = new TreeSet<>();
            for (YearMonth month = YearMonth.from(from);
                    !month.isAfter(endMonth);
                    month = month.plusMonths(1)) {
                months.add(month);
            }
            months.add(YearMonth.from(week.monday()));
            LocalDate nextMonday = week.monday().plusDays(7);
            months.add(YearMonth.from(nextMonday));
            months.add(YearMonth.from(nextMonday.plusDays(6)));
            for (YearMonth month : months) {
                BillsForPeriod bills = sources.bills(actorId, month);
                billsByMonth.put(month, bills);
                for (BillView bill : bills.bills()) {
                    billById.putIfAbsent(bill.id(), bill);
                    for (OccurrenceView occurrence : bill.occurrences()) {
                        occurrences.put(new Target(bill.id(), occurrence.dueDate()), occurrence);
                    }
                }
            }
        }

        private Map<UUID, UUID> debtIds() {
            Map<UUID, UUID> ids = new HashMap<>();
            for (Map.Entry<UUID, DebtView> entry : debtByBill.entrySet()) {
                ids.put(entry.getKey(), entry.getValue().id());
            }
            return ids;
        }

        /**
         * Occurrences an answer already names belong to that answer and are offered to no other.
         */
        private void claimAnsweredTargets() {
            for (CheckInAnswer answer : answers.values()) {
                switch (answer.itemKind()) {
                    case OVERDUE_BILL ->
                            claimed.put(
                                    new Target(
                                            answer.billId().orElseThrow(),
                                            answer.dueDate().orElseThrow()),
                                    answer.itemKey());
                    case UNUSUAL_CHARGE, BILL_CHARGE -> {
                        if (answer.billId().isPresent() && answer.dueDate().isPresent()) {
                            claimed.put(
                                    new Target(answer.billId().get(), answer.dueDate().get()),
                                    answer.itemKey());
                        }
                    }
                    default -> {
                        // Categories and allocations name no occurrence.
                    }
                }
            }
        }

        // ---------------------------------------------------------------------- steps 1 and 3

        /**
         * Unusual charges (step 1) and bill charges (step 3), oldest first so that two charges in
         * one week attach to two occurrences, never one. A charge offered as unusual is one item,
         * in step 1, and is not asked again in step 3.
         */
        private void deriveCharges(List<LedgerRow> outflows) {
            Map<PairKey, ChargeRule> rules = new HashMap<>();
            Set<UUID> mappedBills = new HashSet<>();
            for (ChargeRule rule : chargeRules.findAllForHousehold(householdId)) {
                rules.put(new PairKey(rule.payeeId(), rule.accountId()), rule);
                mappedBills.add(rule.billId());
            }
            fingerprint.add("charge-rules", rules.size());

            Set<UUID> payees = new LinkedHashSet<>();
            for (LedgerRow row : outflows) {
                payees.add(row.payeeId());
            }
            Map<PairKey, List<HistoryRow>> history = new HashMap<>();
            if (!payees.isEmpty()) {
                for (HistoryRow row :
                        ledger.payeeHistory(
                                householdId,
                                code,
                                payees,
                                from.minusMonths(12),
                                to,
                                PageRequest.of(0, HISTORY_ROWS))) {
                    history.computeIfAbsent(
                                    new PairKey(row.payeeId(), row.accountId()),
                                    key -> new ArrayList<>())
                            .add(row);
                }
            }

            List<LedgerRow> oldestFirst = new ArrayList<>(outflows);
            oldestFirst.sort(Comparator.comparing(LedgerRow::date).thenComparing(LedgerRow::id));
            Set<PairKey> latestSeen = new HashSet<>();
            Map<UUID, Boolean> isLatest = new HashMap<>();
            for (LedgerRow row : outflows) {
                // `outflows` is newest first: the first row of each pair is its latest.
                isLatest.put(row.id(), latestSeen.add(new PairKey(row.payeeId(), row.accountId())));
            }

            // One median per pair, not one per row: the history before the window is the same for
            // every row of a pair, and sorting it again for each of 200 rows was 200 sorts of up
            // to 10,000 amounts on every read (slice-14 security audit, low 2).
            Map<PairKey, Optional<BigDecimal>> medians = new HashMap<>();
            for (LedgerRow row : oldestFirst) {
                PairKey pair = new PairKey(row.payeeId(), row.accountId());
                BigDecimal magnitude = row.amount().negate();
                ChargeRule rule = rules.get(pair);
                BillView bill = rule == null ? null : billById.get(rule.billId());
                if (bill != null) {
                    chargeWithRule(row, magnitude, bill);
                } else {
                    List<HistoryRow> pairHistory = history.getOrDefault(pair, List.of());
                    chargeWithoutRule(
                            row,
                            magnitude,
                            rule == null,
                            pairHistory,
                            medians.computeIfAbsent(pair, key -> medianBefore(pairHistory)),
                            Boolean.TRUE.equals(isLatest.get(row.id())),
                            mappedBills);
                }
            }
        }

        /** The median magnitude of a pair's outflows before the window: the "usual" amount. */
        private Optional<BigDecimal> medianBefore(List<HistoryRow> pairHistory) {
            List<BigDecimal> before = new ArrayList<>();
            for (HistoryRow past : pairHistory) {
                if (past.date().isBefore(from)) {
                    before.add(past.amount().negate());
                }
            }
            return ChargeDetection.median(before);
        }

        private void chargeWithRule(LedgerRow row, BigDecimal magnitude, BillView bill) {
            BigDecimal expected = bill.expectedAmount().amount();
            BigDecimal tolerance = bill.amountVaries() ? bill.tolerance() : null;
            if (ChargeDetection.isUnusual(magnitude, expected)) {
                String key = ItemKeys.unusual(row.id());
                OptionalInt multiple =
                        irregular(bill)
                                ? OptionalInt.empty()
                                : ChargeDetection.prepaidMultiple(magnitude, expected, tolerance);
                Target target = null;
                List<LocalDate> skips = List.of();
                if (multiple.isPresent()) {
                    target = nearest(bill, row.date(), key);
                    if (target != null) {
                        consumed.put(target, key);
                        skips = laterDates(bill, target.dueDate(), multiple.getAsInt() - 1);
                    }
                }
                boolean offersPrepaid = target != null;
                Item item =
                        new Item(
                                key,
                                ItemKind.UNUSUAL_CHARGE,
                                true,
                                row,
                                bill.id(),
                                bill.name(),
                                target == null ? null : target.dueDate(),
                                bill.expectedAmount(),
                                row.version(),
                                bill.expectedAmount(),
                                offersPrepaid ? multiple.getAsInt() : null,
                                skips,
                                null,
                                target,
                                target == null ? null : occurrenceVersion(target),
                                null,
                                null,
                                false);
                add(unusual, item);
                if (offersPrepaid) {
                    attachTargets.put(key, Set.of(target));
                }
                return;
            }
            String key = ItemKeys.charge(row.id());
            Target target = nearest(bill, row.date(), key);
            if (target == null) {
                // Nothing of that bill is still due to attach to: it was paid by hand, or skipped.
                return;
            }
            consumed.put(target, key);
            boolean matches = ChargeDetection.withinTolerance(magnitude, expected, tolerance);
            Item item =
                    new Item(
                            key,
                            ItemKind.BILL_CHARGE,
                            !matches,
                            row,
                            bill.id(),
                            bill.name(),
                            target.dueDate(),
                            bill.expectedAmount(),
                            occurrenceVersion(target),
                            null,
                            null,
                            null,
                            matches ? ChargeReason.RULE_MATCH : ChargeReason.RULE_OUTSIDE_TOLERANCE,
                            target,
                            occurrenceVersion(target),
                            null,
                            null,
                            matches);
            add(charges, item);
            attachTargets.put(key, attachableFor(row, key));
        }

        private void chargeWithoutRule(
                LedgerRow row,
                BigDecimal magnitude,
                boolean noRule,
                List<HistoryRow> pairHistory,
                Optional<BigDecimal> median,
                boolean latest,
                Set<UUID> mappedBills) {
            if (median.isPresent() && ChargeDetection.isUnusual(magnitude, median.get())) {
                add(
                        unusual,
                        new Item(
                                ItemKeys.unusual(row.id()),
                                ItemKind.UNUSUAL_CHARGE,
                                true,
                                row,
                                null,
                                null,
                                null,
                                null,
                                row.version(),
                                Money.of(median.get(), base),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                false));
                return;
            }
            if (!noRule || !latest) {
                return;
            }
            // Built only here, for a pair's latest row: once per pair, not once per row.
            List<ChargeDetection.DatedAmount> earlier = new ArrayList<>();
            for (HistoryRow past : pairHistory) {
                if (!past.id().equals(row.id()) && past.date().isBefore(endMonth.atDay(1))) {
                    earlier.add(
                            new ChargeDetection.DatedAmount(past.date(), past.amount().negate()));
                }
            }
            if (!ChargeDetection.isRecurring(row.date(), magnitude, endMonth, earlier)) {
                return;
            }
            String key = ItemKeys.charge(row.id());
            BillView suggestion = null;
            int matching = 0;
            for (BillView bill : activeBills()) {
                if (!mappedBills.contains(bill.id())
                        && ChargeDetection.withinTolerance(
                                magnitude,
                                bill.expectedAmount().amount(),
                                bill.amountVaries() ? bill.tolerance() : null)) {
                    matching++;
                    suggestion = bill;
                }
            }
            Target suggested = null;
            if (matching == 1) {
                suggested = nearest(suggestion, row.date(), key);
            }
            if (suggested != null) {
                consumed.put(suggested, key);
            }
            add(
                    charges,
                    new Item(
                            key,
                            ItemKind.BILL_CHARGE,
                            true,
                            row,
                            suggested == null ? null : suggested.billId(),
                            suggested == null ? null : suggestion.name(),
                            suggested == null ? null : suggested.dueDate(),
                            suggested == null ? null : suggestion.expectedAmount(),
                            null,
                            null,
                            null,
                            null,
                            ChargeReason.RECURRING,
                            null,
                            null,
                            suggested,
                            null,
                            false));
            attachTargets.put(key, attachableFor(row, key));
        }

        /** Every (bill, date) an attach for this row may name: the picker's choices. */
        private Set<Target> attachableFor(LedgerRow row, String key) {
            Set<Target> targets = new HashSet<>();
            for (BillView bill : activeBills()) {
                if (irregular(bill)) {
                    Target self = new Target(bill.id(), row.date());
                    if (available(self, key)) {
                        targets.add(self);
                    }
                    continue;
                }
                for (LocalDate date : dueDatesIn(bill, YearMonth.from(row.date()))) {
                    Target candidate = new Target(bill.id(), date);
                    if (available(candidate, key)) {
                        targets.add(candidate);
                    }
                }
            }
            return targets;
        }

        /** Step 1's overdue bills: still {@code DUE}, past their date, and on or before Sunday. */
        private void deriveOverdue() {
            for (YearMonth month = YearMonth.from(from);
                    !month.isAfter(endMonth);
                    month = month.plusMonths(1)) {
                BillsForPeriod bills = billsByMonth.get(month);
                for (BillView bill : bills.bills()) {
                    for (OccurrenceView occurrence : bill.occurrences()) {
                        Target target = new Target(bill.id(), occurrence.dueDate());
                        String key = ItemKeys.occurrence(bill.id(), occurrence.dueDate());
                        boolean ours = key.equals(claimed.get(target));
                        if (occurrence.status() != OccurrenceStatus.DUE
                                || !occurrence.overdue()
                                || occurrence.dueDate().isAfter(to)
                                || (consumed.containsKey(target) && !ours)
                                || (claimed.containsKey(target) && !ours)) {
                            continue;
                        }
                        add(
                                overdue,
                                new Item(
                                        key,
                                        ItemKind.OVERDUE_BILL,
                                        true,
                                        null,
                                        bill.id(),
                                        bill.name(),
                                        occurrence.dueDate(),
                                        occurrence.expectedAmount(),
                                        occurrence.version(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        target,
                                        occurrence.version(),
                                        null,
                                        null,
                                        false));
                    }
                }
            }
        }

        // -------------------------------------------------------------------------- step 2

        private void deriveCategorize(List<LedgerRow> uncategorised) {
            Set<UUID> payees = new HashSet<>();
            for (LedgerRow row : uncategorised) {
                if (row.payeeId() != null) {
                    payees.add(row.payeeId());
                }
            }
            Map<UUID, PayeeRule> rules = new HashMap<>();
            if (!payees.isEmpty()) {
                for (PayeeRule rule : payeeRules.findForPayees(householdId, payees)) {
                    rules.put(rule.payeeId(), rule);
                }
            }
            for (LedgerRow row : uncategorised) {
                PayeeRule rule = row.payeeId() == null ? null : rules.get(row.payeeId());
                CategoryRow ruled = rule == null ? null : categories.get(rule.categoryId());
                // A rule whose category is archived offers nothing until it is restored.
                boolean preAnswers = ruled != null && !ruled.archived();
                add(
                        categorize,
                        new Item(
                                ItemKeys.categorize(row.id()),
                                ItemKind.CATEGORIZE,
                                !preAnswers,
                                row,
                                null,
                                null,
                                null,
                                null,
                                row.version(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                rule == null ? null : rule.categoryId(),
                                preAnswers));
            }
        }

        // -------------------------------------------------------------------------- income

        /**
         * Information only (decision 7): for each expected arrival in the window, the positive row
         * nearest its amount, ties to the earlier date. Each row pairs with one arrival at most.
         */
        private List<IncomeLine> incomeLines() {
            List<LedgerRow> inflows =
                    ledger.inflows(householdId, code, from, to, PageRequest.of(0, INFLOW_ROWS));
            List<Expected> expected = new ArrayList<>();
            for (YearMonth month = YearMonth.from(from);
                    !month.isAfter(endMonth);
                    month = month.plusMonths(1)) {
                for (IncomeView source : sources.income(actorId, month).sources()) {
                    if (!source.active() || !source.amount().currency().equals(base)) {
                        continue;
                    }
                    for (LocalDate date : source.dates()) {
                        if (!date.isBefore(from) && !date.isAfter(to)) {
                            expected.add(new Expected(source, date));
                        }
                    }
                }
            }
            expected.sort(
                    Comparator.comparing(Expected::date)
                            .thenComparing(arrival -> arrival.source().id()));
            Set<UUID> used = new HashSet<>();
            List<IncomeLine> lines = new ArrayList<>(expected.size());
            for (Expected arrival : expected) {
                IncomeView source = arrival.source();
                LedgerRow best = null;
                BigDecimal bestGap = null;
                for (LedgerRow row : inflows) {
                    if (used.contains(row.id())
                            || (source.accountId() != null
                                    && !source.accountId().equals(row.accountId()))) {
                        continue;
                    }
                    BigDecimal gap = row.amount().subtract(source.amount().amount()).abs();
                    if (best == null || gap.compareTo(bestGap) < 0) {
                        best = row;
                        bestGap = gap;
                    }
                }
                if (best != null) {
                    used.add(best.id());
                }
                lines.add(
                        new IncomeLine(
                                source.id(),
                                source.name(),
                                arrival.date(),
                                source.amount(),
                                best == null ? null : best.id(),
                                best == null ? null : best.date(),
                                best == null ? null : Money.of(best.amount(), base),
                                best == null ? null : best.accountId(),
                                best == null ? null : best.accountName(),
                                best == null ? null : best.accountLastFour()));
                fingerprint.add(
                        "income",
                        source.id(),
                        arrival.date(),
                        source.amount(),
                        best == null ? null : best.id(),
                        best == null ? null : best.version());
            }
            return lines;
        }

        // -------------------------------------------------------------------------- step 4

        private Figures figures() {
            LocalDate monday = week.monday();
            LocalDate nextMonday = monday.plusDays(7);
            YearMonth first = YearMonth.from(nextMonday);
            LocalDate scanFrom = monday.isBefore(endMonth.atDay(1)) ? monday : endMonth.atDay(1);
            List<SpendingGroup> groups = ledger.spendingGroups(householdId, code, scanFrom, to);

            Set<YearMonth> spanned = new TreeSet<>();
            spanned.add(YearMonth.from(scanFrom));
            spanned.add(endMonth);
            UnbudgetedSpending rule = new UnbudgetedSpending(base, categories, budgetedIn(spanned));

            Money weekSpending = applyAnswers(rule, rule.between(groups, monday, to), monday, to);
            LocalDate monthFrom = first.atDay(1);
            LocalDate monthTo = to.isBefore(first.atEndOfMonth()) ? to : first.atEndOfMonth();
            Money unbudgetedMonth =
                    monthTo.isBefore(monthFrom)
                            ? Money.zero(base)
                            : applyAnswers(
                                    rule,
                                    rule.between(groups, monthFrom, monthTo),
                                    monthFrom,
                                    monthTo);

            List<WeeklyFigure.MonthTerms> months = new ArrayList<>();
            months.add(
                    new WeeklyFigure.MonthTerms(
                            first,
                            sources.left(actorId, first),
                            unbudgetedMonth,
                            sources.checkInDebtPayments(actorId, first)));
            YearMonth second = YearMonth.from(nextMonday.plusDays(6));
            if (!second.equals(first)) {
                months.add(
                        new WeeklyFigure.MonthTerms(
                                second,
                                sources.left(actorId, second),
                                Money.zero(base),
                                Money.zero(base)));
            }

            List<CheckIn> before =
                    checkIns.findCompletedBefore(
                            householdId, monday, PageRequest.of(0, STREAK_WEEKS));
            Money weekTarget = null;
            if (!before.isEmpty()
                    && before.getFirst().week().equals(week.previous())
                    && before.getFirst().nextWeekTarget().isPresent()) {
                weekTarget = Money.of(before.getFirst().nextWeekTarget().get(), base);
            }
            Money surplus = weekTarget == null ? null : weekTarget.minus(weekSpending);
            WeeklyFigure.Result result = WeeklyFigure.of(nextMonday, months, surplus);
            YearMonth debtFree = sources.debtFreeMonth(actorId).orElse(null);

            Figures figures = new Figures();
            figures.weekTarget = weekTarget;
            figures.weekSpending = weekSpending;
            figures.surplus = surplus;
            figures.result = result;
            figures.debtFreeMonth = debtFree;
            figures.soFar = !today.isAfter(to);
            figures.streak = streak(before, surplus, debtFree);
            fingerprint.add(
                    "figures",
                    weekTarget,
                    weekSpending,
                    surplus,
                    result.free(),
                    result.base(),
                    result.rolledOver(),
                    debtFree);

            if (surplus != null && surplus.isPositive()) {
                offerChoices(figures, surplus, result.surplusAllocatable());
            }
            return figures;
        }

        /**
         * Applies the draft's answers, and the rules' pre-answers, to a total: a row answered
         * "Dining" counts as Dining, and a row attached in step 1 or 3 drops out.
         */
        private Money applyAnswers(
                UnbudgetedSpending rule, Money total, LocalDate rangeFrom, LocalDate rangeTo) {
            Map<UUID, LedgerRow> rows = new HashMap<>();
            Map<UUID, UUID> categoryAfter = new HashMap<>();
            Set<UUID> attachedAfter = new HashSet<>();
            for (Item item : categorize) {
                rows.put(item.row().id(), item.row());
                CheckInAnswer answer = answers.get(item.key());
                if (answer != null && answer.choice() == AnswerChoice.CATEGORY) {
                    categoryAfter.put(item.row().id(), answer.categoryId().orElseThrow());
                } else if (answer == null && item.rulePreAnswers()) {
                    categoryAfter.put(item.row().id(), item.ruleCategoryId());
                }
            }
            for (Item item : charges) {
                rows.put(item.row().id(), item.row());
                CheckInAnswer answer = answers.get(item.key());
                if ((answer != null && answer.choice() == AnswerChoice.ATTACH)
                        || (answer == null && item.reason() == ChargeReason.RULE_MATCH)) {
                    attachedAfter.add(item.row().id());
                }
            }
            for (Item item : unusual) {
                rows.put(item.row().id(), item.row());
                CheckInAnswer answer = answers.get(item.key());
                if (answer != null && answer.choice() == AnswerChoice.PREPAID) {
                    attachedAfter.add(item.row().id());
                }
            }
            Money adjusted = total;
            for (LedgerRow row : rows.values()) {
                UUID category = categoryAfter.getOrDefault(row.id(), row.categoryId());
                boolean attached = attachedAfter.contains(row.id());
                adjusted =
                        rule.adjusted(
                                adjusted,
                                rangeFrom,
                                rangeTo,
                                row.date(),
                                row.amount(),
                                row.categoryId(),
                                category,
                                attached,
                                !row.attached() && !row.transfer());
            }
            return adjusted;
        }

        /** Per month, the categories an unarchived envelope or an active bill already budgets. */
        private Map<YearMonth, Set<UUID>> budgetedIn(Collection<YearMonth> months) {
            Map<YearMonth, Set<UUID>> budgeted = new HashMap<>();
            List<LocalDate> periods = new ArrayList<>();
            for (YearMonth month : months) {
                budgeted.put(month, new HashSet<>());
                periods.add(month.atDay(1));
                BillsForPeriod bills = billsByMonth.get(month);
                if (bills == null) {
                    continue;
                }
                for (BillView bill : bills.bills()) {
                    if (bill.categoryId() == null) {
                        continue;
                    }
                    for (OccurrenceView occurrence : bill.occurrences()) {
                        if (occurrence.status() != OccurrenceStatus.SKIPPED) {
                            budgeted.get(month).add(bill.categoryId());
                            break;
                        }
                    }
                }
            }
            for (EnvelopeCategory envelope : ledger.envelopeCategories(householdId, periods)) {
                budgeted.computeIfAbsent(YearMonth.from(envelope.period()), key -> new HashSet<>())
                        .add(envelope.categoryId());
            }
            for (YearMonth month : new TreeSet<>(budgeted.keySet())) {
                fingerprint.add("budgeted", month, new TreeSet<>(budgeted.get(month)));
            }
            return budgeted;
        }

        /**
         * The surplus's destinations. Nothing is preselected (an assumption the feature doc
         * records). A debt or a goal only when the month can cover it.
         */
        private void offerChoices(Figures figures, Money surplus, boolean allocatable) {
            List<ChoiceView> choices = new ArrayList<>();
            if (allocatable) {
                List<DebtView> debts = new ArrayList<>();
                List<DebtView> active = new ArrayList<>();
                for (DebtView debt : sources.debts(actorId)) {
                    if (debt.currency().equals(code)
                            && debt.paidOffAt() == null
                            && !debt.archived()
                            && debt.balance().isPositive()) {
                        active.add(debt);
                        if (debt.rung() != null && debt.rung() == 1) {
                            debts.add(debt);
                        }
                    }
                }
                if (!sources.hasPlan(actorId)) {
                    debts = active;
                }
                for (DebtView debt : debts) {
                    CheckInSources.DebtChoiceFigures moved =
                            sources.oneOffPayment(actorId, debt.id(), surplus);
                    choices.add(
                            new ChoiceView(
                                    AnswerChoice.DEBT,
                                    debt.id(),
                                    debt.name(),
                                    moved.payoffMonthNow(),
                                    moved.payoffMonthWith(),
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    moved.monthInterest()));
                    figures.offeredDebts.add(debt.id());
                    fingerprint.add(
                            "debt",
                            debt.id(),
                            moved.payoffMonthNow(),
                            moved.payoffMonthWith(),
                            moved.monthInterest());
                }
                for (GoalView goal : sources.goals(actorId)) {
                    if (goal.archived()
                            || !goal.currency().equals(base)
                            || goal.figures().reached()) {
                        continue;
                    }
                    Money saved = goal.figures().saved();
                    choices.add(
                            new ChoiceView(
                                    AnswerChoice.GOAL,
                                    null,
                                    null,
                                    null,
                                    null,
                                    goal.id(),
                                    goal.name(),
                                    saved,
                                    saved.plus(surplus),
                                    goal.targetAmount(),
                                    null));
                    figures.offeredGoals.add(goal.id());
                    fingerprint.add("goal", goal.id(), saved);
                }
            }
            choices.add(
                    new ChoiceView(
                            AnswerChoice.ROLL_OVER,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null));
            figures.choices = choices;
            figures.allocation =
                    new Item(
                            ItemKeys.ALLOCATION,
                            ItemKind.ALLOCATION,
                            true,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            surplus,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            false);
        }

        /**
         * Consecutive completed weeks with {@code surplus ≥ 0}, this week first when it has a
         * target and came in under. The debt-free half compares the month now with the one stored
         * on the streak's first check-in, and is shown only when both exist and differ.
         */
        private StreakView streak(List<CheckIn> before, Money surplus, YearMonth debtFreeNow) {
            if (surplus == null || surplus.isNegative()) {
                return new StreakView(0, null, null);
            }
            int weeks = 1;
            IsoWeek expected = week.previous();
            CheckIn first = null;
            for (CheckIn earlier : before) {
                if (!earlier.week().equals(expected)
                        || earlier.weekTarget().isEmpty()
                        || earlier.weekSpending().isEmpty()
                        || earlier.weekSpending().get().compareTo(earlier.weekTarget().get()) > 0) {
                    break;
                }
                weeks++;
                first = earlier;
                expected = expected.previous();
            }
            YearMonth then = first == null ? null : first.debtFreeMonth().orElse(null);
            boolean moved = then != null && debtFreeNow != null && !then.equals(debtFreeNow);
            return new StreakView(weeks, moved ? then : null, moved ? debtFreeNow : null);
        }

        // ---------------------------------------------------------------------------- views

        private StepView step(
                Step step,
                List<Item> items,
                Map<UUID, String> names,
                List<IncomeLine> income,
                Long more,
                Money weekSpending) {
            List<ItemView> views = new ArrayList<>(items.size());
            int questions = 0;
            int answeredCount = 0;
            for (Item item : items) {
                CheckInAnswer answer = answers.get(item.key());
                if (item.question()) {
                    questions++;
                    if (answer != null) {
                        answeredCount++;
                    }
                }
                views.add(itemView(item, answer, names));
            }
            return new StepView(
                    step,
                    answeredCount == questions,
                    questions,
                    answeredCount,
                    views,
                    income,
                    more,
                    weekSpending,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        private StepView billsStep(Map<UUID, String> names) {
            StepView items = step(Step.BILLS, charges, names, null, null, null);
            List<OccurrenceLine> table = new ArrayList<>();
            BillsForPeriod month = billsByMonth.get(endMonth);
            for (YearMonth earlier = YearMonth.from(from);
                    earlier.isBefore(endMonth);
                    earlier = earlier.plusMonths(1)) {
                for (BillView bill : billsByMonth.get(earlier).bills()) {
                    for (OccurrenceView occurrence : bill.occurrences()) {
                        if (!occurrence.dueDate().isBefore(from)) {
                            table.add(line(bill, occurrence));
                        }
                    }
                }
            }
            for (BillView bill : month.bills()) {
                for (OccurrenceView occurrence : bill.occurrences()) {
                    table.add(line(bill, occurrence));
                }
            }
            table.sort(
                    Comparator.comparing(OccurrenceLine::dueDate)
                            .thenComparing(OccurrenceLine::billName));
            for (OccurrenceLine line : table) {
                fingerprint.add(
                        "table", line.billId(), line.dueDate(), line.status(), line.amount());
            }
            List<BillChoice> choices = new ArrayList<>();
            for (BillView bill : activeBills()) {
                Set<LocalDate> dates = new TreeSet<>();
                for (YearMonth each = YearMonth.from(from);
                        !each.isAfter(endMonth);
                        each = each.plusMonths(1)) {
                    if (!irregular(bill)) {
                        dates.addAll(dueDatesIn(bill, each));
                    }
                }
                choices.add(
                        new BillChoice(
                                bill.id(),
                                bill.name(),
                                bill.expectedAmount(),
                                bill.amountVaries(),
                                bill.tolerance(),
                                List.copyOf(dates)));
            }
            return new StepView(
                    Step.BILLS,
                    items.answered(),
                    items.questions(),
                    items.answeredCount(),
                    items.items(),
                    null,
                    null,
                    null,
                    table,
                    month.outstanding(),
                    choices,
                    null,
                    null,
                    null,
                    null);
        }

        private StepView nextWeekStep(Figures figures, Map<UUID, String> names) {
            WeeklyFigure.Result result = figures.result;
            LocalDate nextMonday = week.monday().plusDays(7);
            LocalDate nextSunday = nextMonday.plusDays(6);
            List<DueLine> due = new ArrayList<>();
            Set<Target> seen = new HashSet<>();
            for (YearMonth month :
                    new TreeSet<>(
                            List.of(YearMonth.from(nextMonday), YearMonth.from(nextSunday)))) {
                for (BillView bill : billsByMonth.get(month).bills()) {
                    for (OccurrenceView occurrence : bill.occurrences()) {
                        if (occurrence.status() == OccurrenceStatus.DUE
                                && !occurrence.dueDate().isBefore(nextMonday)
                                && !occurrence.dueDate().isAfter(nextSunday)
                                && seen.add(new Target(bill.id(), occurrence.dueDate()))) {
                            due.add(new DueLine(bill.id(), bill.name(), occurrence.dueDate()));
                        }
                    }
                }
            }
            due.sort(Comparator.comparing(DueLine::dueDate).thenComparing(DueLine::billName));
            List<ItemView> items = new ArrayList<>();
            boolean answered = true;
            int questions = 0;
            int answeredCount = 0;
            if (figures.allocation != null) {
                CheckInAnswer answer = answers.get(ItemKeys.ALLOCATION);
                items.add(itemView(figures.allocation, answer, names));
                questions = 1;
                answeredCount = answer == null ? 0 : 1;
                answered = answer != null;
            }
            return new StepView(
                    Step.NEXT_WEEK,
                    answered,
                    questions,
                    answeredCount,
                    items,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    new FiguresView(
                            figures.weekTarget,
                            figures.weekSpending,
                            figures.surplus,
                            result.free(),
                            result.base(),
                            result.basePerDay(),
                            result.rolledOver(),
                            result.rolledPerDay(),
                            result.surplusAllocatable(),
                            figures.soFar),
                    figures.choices,
                    due,
                    figures.streak);
        }

        private ItemView itemView(Item item, CheckInAnswer answer, Map<UUID, String> names) {
            LedgerRow row = item.row();
            PreAnswer preAnswer = null;
            if (item.kind() == ItemKind.CATEGORIZE && item.rulePreAnswers()) {
                CategoryRow category = categories.get(item.ruleCategoryId());
                preAnswer =
                        new PreAnswer(
                                AnswerChoice.CATEGORY,
                                category.id(),
                                category.name(),
                                null,
                                null,
                                null);
            } else if (item.reason() == ChargeReason.RULE_MATCH) {
                preAnswer =
                        new PreAnswer(
                                AnswerChoice.ATTACH,
                                null,
                                null,
                                item.billId(),
                                item.billName(),
                                item.dueDate());
            }
            DebtView debt = item.billId() == null ? null : debtByBill.get(item.billId());
            CategoryRow ruled =
                    item.ruleCategoryId() == null ? null : categories.get(item.ruleCategoryId());
            return new ItemView(
                    item.key(),
                    item.kind(),
                    item.question(),
                    row == null ? null : row.id(),
                    row == null ? null : row.version(),
                    row == null ? null : row.date(),
                    // An allocation's amount is the surplus, the one figure its answer must repeat.
                    row == null
                            ? (item.kind() == ItemKind.ALLOCATION ? item.usual() : null)
                            : Money.of(row.amount(), base),
                    row == null ? null : row.payeeName(),
                    row == null ? null : row.accountId(),
                    row == null ? null : row.accountName(),
                    row == null ? null : row.accountLastFour(),
                    item.billId(),
                    item.billName(),
                    item.dueDate(),
                    item.expected(),
                    item.kind() == ItemKind.ALLOCATION ? null : item.usual(),
                    item.multiple(),
                    item.multiple() == null ? null : item.skips().size(),
                    item.reason(),
                    item.suggested() == null ? null : item.suggested().billId(),
                    item.suggested() == null ? null : item.suggested().dueDate(),
                    debt == null ? null : new DebtPrompt(debt.id(), debt.name()),
                    ruled == null ? null : ruled.id(),
                    ruled == null ? null : ruled.name(),
                    preAnswer,
                    answer == null ? null : answerView(answer, names));
        }

        private AnswerView answerView(CheckInAnswer answer, Map<UUID, String> names) {
            CategoryRow category = answer.categoryId().map(categories::get).orElse(null);
            BillView bill = answer.billId().map(billById::get).orElse(null);
            return new AnswerView(
                    answer.choice(),
                    answer.categoryId().orElse(null),
                    category == null ? null : category.name(),
                    answer.itemKind() == ItemKind.OVERDUE_BILL
                            ? null
                            : answer.billId().orElse(null),
                    answer.itemKind() == ItemKind.OVERDUE_BILL || bill == null ? null : bill.name(),
                    answer.itemKind() == ItemKind.OVERDUE_BILL
                            ? null
                            : answer.dueDate().orElse(null),
                    answer.goalId().orElse(null),
                    answer.debtId().orElse(null),
                    answer.amount().map(amount -> Money.of(amount, base)).orElse(null),
                    answer.remember(),
                    answer.recordDebtPayment(),
                    answer.isByRule(),
                    person(answer.answeredBy().orElse(null), names),
                    answer.version());
        }

        // ------------------------------------------------------------------------- helpers

        private void add(List<Item> step, Item item) {
            if (step.size() >= ITEMS_PER_STEP) {
                return;
            }
            step.add(item);
            LedgerRow row = item.row();
            fingerprint.add(
                    item.key(),
                    item.question(),
                    row == null ? null : row.version(),
                    row == null ? null : row.amount(),
                    row == null ? null : row.date(),
                    row == null ? null : row.categoryId(),
                    item.billId(),
                    item.dueDate(),
                    item.expected(),
                    item.subjectVersion(),
                    item.usual(),
                    item.multiple(),
                    item.skips(),
                    item.reason(),
                    item.target(),
                    item.suggested(),
                    item.ruleCategoryId(),
                    item.rulePreAnswers());
        }

        private List<BillView> activeBills() {
            List<BillView> active = new ArrayList<>();
            for (BillView bill : billsByMonth.get(endMonth).bills()) {
                if (bill.active() && bill.expectedAmount().currency().equals(base)) {
                    active.add(bill);
                }
            }
            return active;
        }

        private boolean irregular(BillView bill) {
            return bill.recurrence().kind() == ScheduleKind.IRREGULAR;
        }

        /** The bill's {@code DUE} occurrences in a month, as Bills &amp; Income lists them. */
        private List<LocalDate> dueDatesIn(BillView bill, YearMonth month) {
            List<LocalDate> dates = new ArrayList<>();
            BillsForPeriod bills = billsByMonth.get(month);
            if (bills == null) {
                return dates;
            }
            for (BillView listed : bills.bills()) {
                if (!listed.id().equals(bill.id())) {
                    continue;
                }
                for (OccurrenceView occurrence : listed.occurrences()) {
                    if (occurrence.status() == OccurrenceStatus.DUE
                            && occurrence.transactionId() == null) {
                        dates.add(occurrence.dueDate());
                    }
                }
            }
            return dates;
        }

        private boolean available(Target target, String key) {
            String owner = claimed.get(target);
            String user = consumed.get(target);
            return (owner == null || owner.equals(key)) && (user == null || user.equals(key));
        }

        /**
         * The bill's unattached {@code DUE} occurrence nearest the charge's date in its month, ties
         * to the earlier date; for an {@code IRREGULAR} bill, the charge's own date, which brings
         * the occurrence into being (bills-and-income.md).
         */
        private Target nearest(BillView bill, LocalDate date, String key) {
            if (irregular(bill)) {
                Target self = new Target(bill.id(), date);
                OccurrenceView existing = occurrences.get(self);
                if (existing != null && existing.status() != OccurrenceStatus.DUE) {
                    return null;
                }
                return available(self, key) ? self : null;
            }
            Target best = null;
            long bestGap = Long.MAX_VALUE;
            for (LocalDate due : dueDatesIn(bill, YearMonth.from(date))) {
                Target candidate = new Target(bill.id(), due);
                if (!available(candidate, key)) {
                    continue;
                }
                long gap = Math.abs(due.toEpochDay() - date.toEpochDay());
                if (gap < bestGap || (gap == bestGap && due.isBefore(best.dueDate()))) {
                    best = candidate;
                    bestGap = gap;
                }
            }
            return best;
        }

        /**
         * The next {@code count} dates the bill's schedule projects after {@code after}, looking at
         * most twelve months ahead: a yearly bill skips the ones that project and no more.
         */
        private List<LocalDate> laterDates(BillView bill, LocalDate after, int count) {
            List<LocalDate> dates = new ArrayList<>();
            YearMonth month = YearMonth.from(after);
            YearMonth last = month.plusMonths(12);
            while (dates.size() < count && !month.isAfter(last)) {
                for (LocalDate date : bill.recurrence().datesIn(month)) {
                    if (date.isAfter(after) && dates.size() < count) {
                        dates.add(date);
                    }
                }
                month = month.plusMonths(1);
            }
            return dates;
        }

        private Long occurrenceVersion(Target target) {
            OccurrenceView occurrence = occurrences.get(target);
            return occurrence == null ? null : occurrence.version();
        }

        private OccurrenceLine line(BillView bill, OccurrenceView occurrence) {
            return new OccurrenceLine(
                    bill.id(),
                    bill.name(),
                    occurrence.dueDate(),
                    occurrence.status(),
                    occurrence.status() == OccurrenceStatus.PAID
                                    && occurrence.actualAmount() != null
                            ? occurrence.actualAmount()
                            : occurrence.expectedAmount(),
                    occurrence.overdue());
        }

        private boolean hasQuestion(List<Item> items) {
            for (Item item : items) {
                if (item.question()) {
                    return true;
                }
            }
            return false;
        }
    }

    static Person person(UUID id, Map<UUID, String> names) {
        return id == null ? null : new Person(id, names.get(id));
    }

    /** A payee in one account: what a charge rule and a payee's history are keyed by. */
    private record PairKey(UUID payeeId, UUID accountId) {}

    private record Expected(IncomeView source, LocalDate date) {}

    /** Step 4's working figures, filled in as they are derived. */
    private static final class Figures {
        Money weekTarget;
        Money weekSpending;
        Money surplus;
        WeeklyFigure.Result result;
        YearMonth debtFreeMonth;
        boolean soFar;
        StreakView streak;
        List<ChoiceView> choices;
        Item allocation;
        final Set<UUID> offeredDebts = new HashSet<>();
        final Set<UUID> offeredGoals = new HashSet<>();
    }

    /**
     * The basis: SHA-256 over a canonical line per derived fact, in derivation order. Not an
     * authorization token; it protects against nothing an attacker does, and is never logged. It
     * exists so a draft cannot be completed against a picture of the week that is no longer true.
     */
    private static final class Fingerprint {

        private final StringBuilder text = new StringBuilder();

        void add(String label, Object... parts) {
            text.append(label);
            for (Object part : parts) {
                text.append('|').append(canonical(part));
            }
            text.append('\n');
        }

        private static String canonical(Object part) {
            if (part == null) {
                return "-";
            }
            if (part instanceof Money money) {
                return money.amount().toPlainString() + money.currency().getCurrencyCode();
            }
            if (part instanceof BigDecimal decimal) {
                return decimal.stripTrailingZeros().toPlainString();
            }
            return part.toString();
        }

        String digest() {
            try {
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                return HexFormat.of()
                        .formatHex(sha.digest(text.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException unavailable) {
                throw new IllegalStateException("SHA-256 is required by the platform", unavailable);
            }
        }

        @Override
        public String toString() {
            return "Fingerprint[]";
        }
    }
}
