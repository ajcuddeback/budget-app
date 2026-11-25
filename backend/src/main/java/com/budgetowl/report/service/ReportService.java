package com.budgetowl.report.service;

import com.budgetowl.category.domain.CategoryKind;
import com.budgetowl.common.CurrencyMismatchException;
import com.budgetowl.common.Money;
import com.budgetowl.household.domain.Household;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.service.MembershipService;
import com.budgetowl.report.persistence.CategoryTotals;
import com.budgetowl.report.persistence.MonthTotals;
import com.budgetowl.report.persistence.ReportRepository;
import com.budgetowl.report.service.IncomeAndSpendingReport.BestMonth;
import com.budgetowl.report.service.IncomeAndSpendingReport.CurrencyFigures;
import com.budgetowl.report.service.IncomeAndSpendingReport.MonthFigures;
import com.budgetowl.report.service.SpendingByCategoryReport.CategorySpending;
import com.budgetowl.report.service.SpendingByCategoryReport.CurrencySpending;
import com.budgetowl.report.service.SpendingByCategoryReport.Uncategorised;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reports: what actually happened in the ledger, across months (docs/features/reporting.md).
 *
 * <p><b>Computed on request, never stored or cached.</b> Every figure is summed from {@code
 * transactions} on each call. A cache would go stale on a dozen paths — a transaction edit, a
 * category delete the database performs on its own ({@code ON DELETE SET NULL}), a category move,
 * an import, a pairing — and a stale report looks exactly as plausible as a right one.
 *
 * <p>Scoping works as everywhere else: the household comes from the caller's verified membership
 * and never from the request, a caller with none gets {@code 403 not-a-member} before any query
 * runs, and every repository call is given that household's id. Every role may read, a {@code
 * VIEWER} included — a {@code VIEWER} already reads every row these figures are summed from — and
 * nothing here writes.
 *
 * <p><b>Per currency, never across.</b> Each currency is a complete report; there is no converted
 * or combined figure (ADR-0022, ADR-0039). All arithmetic is {@link Money}, so summing two
 * currencies throws rather than producing a number.
 *
 * <p><b>Nothing here logs a figure or a name.</b> The one log line is at {@code DEBUG} and carries
 * the user id, the report and the two periods.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private static final int RATIO_SCALE = 4;

    private final MembershipService memberships;
    private final ReportRepository reports;
    private final Clock clock;

    public ReportService(MembershipService memberships, ReportRepository reports, Clock clock) {
        this.memberships = memberships;
        this.reports = reports;
        this.clock = clock;
    }

    /**
     * Report 1: income, spending, the uncategorised terms and kept, for every month in the range,
     * per currency — with the average and the best month over the complete months.
     *
     * @throws com.budgetowl.common.ForbiddenException {@code not-a-member}, before any query
     * @throws com.budgetowl.common.InvalidRequestException {@code report-range-invalid} or {@code
     *     report-range-too-long}, before any report query
     */
    @Transactional(readOnly = true)
    public IncomeAndSpendingReport incomeAndSpending(UUID actorId, YearMonth from, YearMonth to) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        ReportRange range = new ReportRange(from, to);
        Household household = membership.household();
        log.debug(
                "report read report=income-and-spending userId={} from={} to={}",
                actorId,
                range.from(),
                range.to());

        Map<Currency, Map<YearMonth, MonthTotals>> byCurrency = new HashMap<>();
        for (MonthTotals row :
                reports.monthTotals(household.id(), range.firstDay(), range.endExclusive())) {
            byCurrency
                    .computeIfAbsent(Currency.getInstance(row.currency()), any -> new HashMap<>())
                    .put(YearMonth.of(row.year(), row.month()), row);
        }

        YearMonth current = YearMonth.now(clock);
        List<CurrencyFigures> currencies = new ArrayList<>();
        for (Currency currency : inReportOrder(household.baseCurrency(), byCurrency.keySet())) {
            currencies.add(
                    figuresFor(
                            currency, range, byCurrency.getOrDefault(currency, Map.of()), current));
        }
        return new IncomeAndSpendingReport(range, household.baseCurrency(), currencies);
    }

    /**
     * Report 2: spending per expense category over the range, against the preceding range of the
     * same length, as a two-level tree per currency, with the uncategorised outflow beside it.
     *
     * @throws com.budgetowl.common.ForbiddenException {@code not-a-member}, before any query
     * @throws com.budgetowl.common.InvalidRequestException {@code report-range-invalid} or {@code
     *     report-range-too-long}, before any report query
     */
    @Transactional(readOnly = true)
    public SpendingByCategoryReport spendingByCategory(UUID actorId, YearMonth from, YearMonth to) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        ReportRange range = new ReportRange(from, to);
        ReportRange compared = range.preceding();
        Household household = membership.household();
        log.debug(
                "report read report=spending-by-category userId={} from={} to={}",
                actorId,
                range.from(),
                range.to());

        Map<Currency, List<CategoryTotals>> byCurrency = new HashMap<>();
        for (CategoryTotals row :
                reports.categoryTotals(
                        household.id(),
                        compared.firstDay(),
                        range.firstDay(),
                        range.endExclusive())) {
            byCurrency
                    .computeIfAbsent(Currency.getInstance(row.currency()), any -> new ArrayList<>())
                    .add(row);
        }

        List<CurrencySpending> currencies = new ArrayList<>();
        for (Currency currency : inReportOrder(household.baseCurrency(), byCurrency.keySet())) {
            currencies.add(spendingFor(currency, byCurrency.getOrDefault(currency, List.of())));
        }
        boolean complete = range.to().isBefore(YearMonth.now(clock));
        return new SpendingByCategoryReport(
                range, compared, complete, household.baseCurrency(), currencies);
    }

    // ------------------------------------------------------------------------------- report 1

    static CurrencyFigures figuresFor(
            Currency currency,
            ReportRange range,
            Map<YearMonth, MonthTotals> rows,
            YearMonth current) {
        List<MonthFigures> months = new ArrayList<>(range.months());
        long uncategorisedCount = 0;
        long transactionCount = 0;
        for (YearMonth period : range.eachMonth()) {
            MonthTotals row = rows.get(period);
            boolean complete = period.isBefore(current);
            if (row == null) {
                Money zero = Money.zero(currency);
                months.add(new MonthFigures(period, zero, zero, zero, zero, zero, complete));
                continue;
            }
            MonthFigures month = monthOf(period, row, complete);
            if (!month.kept().currency().equals(currency)) {
                throw new CurrencyMismatchException(currency, month.kept().currency());
            }
            months.add(month);
            uncategorisedCount += row.uncategorisedCount();
            transactionCount += row.count();
        }

        Optional<Money> averageKept = Optional.empty();
        Optional<BestMonth> bestMonth = Optional.empty();
        int completeMonths = 0;
        Money keptOverCompleteMonths = Money.zero(currency);
        for (MonthFigures month : months) {
            if (!month.complete()) {
                continue;
            }
            completeMonths++;
            keptOverCompleteMonths = keptOverCompleteMonths.plus(month.kept());
            // Strictly greater, so a tie keeps the earlier month and two reads of the same data
            // name the same best month.
            if (bestMonth.isEmpty() || month.kept().compareTo(bestMonth.get().kept()) > 0) {
                bestMonth = Optional.of(new BestMonth(month.period(), month.kept()));
            }
        }
        if (completeMonths > 0) {
            averageKept =
                    Optional.of(
                            Money.of(
                                    keptOverCompleteMonths
                                            .amount()
                                            .divide(
                                                    BigDecimal.valueOf(completeMonths),
                                                    Money.SCALE,
                                                    RoundingMode.HALF_EVEN),
                                    currency));
        }
        return new CurrencyFigures(
                currency,
                months,
                averageKept,
                bestMonth,
                completeMonths,
                uncategorisedCount,
                transactionCount);
    }

    /**
     * One month's figures, with {@code kept} computed from the four terms and then checked against
     * the plain sum of every row.
     *
     * <p>The two can only disagree if a row reached the sum without reaching one of the terms — a
     * category kind this code does not know, say. That would be a report whose headline figure does
     * not add up, so it fails loudly rather than being shown.
     */
    private static MonthFigures monthOf(YearMonth period, MonthTotals row, boolean complete) {
        // The row's own currency, not the group's: a row grouped under the wrong currency then
        // throws CurrencyMismatchException below instead of being added in as if it matched.
        Currency currency = Currency.getInstance(row.currency());
        Money income = Money.of(row.income(), currency);
        Money spending = Money.of(row.expense(), currency).negated();
        Money uncategorisedIn = Money.of(row.uncategorisedIn(), currency);
        Money uncategorisedOut = Money.of(row.uncategorisedOut(), currency).negated();
        Money kept = income.minus(spending).plus(uncategorisedIn).minus(uncategorisedOut);
        if (!kept.equals(Money.of(row.net(), currency))) {
            throw new IllegalStateException(
                    "report figures for " + period + " do not reconcile with the ledger");
        }
        return new MonthFigures(
                period, income, spending, uncategorisedIn, uncategorisedOut, kept, complete);
    }

    // ------------------------------------------------------------------------------- report 2

    static CurrencySpending spendingFor(Currency currency, List<CategoryTotals> rows) {
        Map<UUID, TopLevel> tree = new LinkedHashMap<>();
        Uncategorised uncategorised = new Uncategorised(Money.zero(currency), 0);
        for (CategoryTotals row : rows) {
            if (row.categoryId() == null) {
                Money outflow = Money.of(row.outflow(), Currency.getInstance(row.currency()));
                uncategorised =
                        new Uncategorised(Money.zero(currency).minus(outflow), row.outflowCount());
            } else if (row.kind() == CategoryKind.EXPENSE) {
                place(currency, row, tree);
            }
            // An INCOME row is in the scan only so an income-only currency still appears. Income
            // by category is not in the first cut (docs/features/reporting.md, Report 2).
        }

        List<Assembled> tops = new ArrayList<>();
        Money positiveTotal = Money.zero(currency);
        Money spending = Money.zero(currency);
        Money comparedSpending = Money.zero(currency);
        for (TopLevel top : tree.values()) {
            Assembled assembled = top.assemble();
            if (!assembled.isShown()) {
                continue;
            }
            tops.add(assembled);
            spending = spending.plus(assembled.spending());
            comparedSpending = comparedSpending.plus(assembled.comparedSpending());
            if (assembled.spending().isPositive()) {
                positiveTotal = positiveTotal.plus(assembled.spending());
            }
        }

        List<CategorySpending> categories = new ArrayList<>();
        for (Assembled top : sorted(tops)) {
            List<CategorySpending> children = new ArrayList<>();
            for (Assembled child : sorted(top.children())) {
                children.add(child.toRow(positiveTotal, List.of()));
            }
            categories.add(top.toRow(positiveTotal, children));
        }
        return new CurrencySpending(
                currency, spending, comparedSpending, categories, uncategorised);
    }

    private static void place(Currency currency, CategoryTotals row, Map<UUID, TopLevel> tree) {
        // The row's own currency, not the group's, for the reason monthOf gives.
        Currency own = Currency.getInstance(row.currency());
        Money amount = Money.of(row.amount(), own);
        Money compared = Money.of(row.comparedAmount(), own);
        if (row.parentId() == null) {
            tree.computeIfAbsent(
                            row.categoryId(),
                            id -> new TopLevel(id, row.name(), row.archived(), currency))
                    .addDirect(amount, compared);
        } else {
            tree.computeIfAbsent(
                            row.parentId(),
                            id ->
                                    new TopLevel(
                                            id, row.parentName(), row.parentArchived(), currency))
                    .addChild(
                            new Assembled(
                                    row.categoryId(),
                                    row.name(),
                                    row.archived(),
                                    amount.negated(),
                                    compared.negated(),
                                    amount.negated(),
                                    List.of()));
        }
    }

    /** Spending descending, then name, then id: deterministic, like the debt plan's ladder. */
    private static List<Assembled> sorted(List<Assembled> rows) {
        return rows.stream()
                .sorted(
                        Comparator.comparing(Assembled::spending, Comparator.reverseOrder())
                                .thenComparing(Assembled::name)
                                .thenComparing(Assembled::categoryId))
                .toList();
    }

    /**
     * {@code (spending − compared) ÷ |compared|}, or empty against zero: a rise from nothing has no
     * percentage.
     */
    static Optional<BigDecimal> change(Money spending, Money compared) {
        if (compared.isZero()) {
            return Optional.empty();
        }
        return Optional.of(
                spending.minus(compared)
                        .amount()
                        .divide(compared.amount().abs(), RATIO_SCALE, RoundingMode.HALF_EVEN));
    }

    /**
     * The row's fraction of the currency's positive spending, or empty for a row whose spending is
     * zero or negative: a refund-heavy category has no slice of a bar.
     */
    static Optional<BigDecimal> share(Money spending, Money positiveTotal) {
        if (!spending.isPositive() || !positiveTotal.isPositive()) {
            return Optional.empty();
        }
        return Optional.of(
                spending.amount()
                        .divide(positiveTotal.amount(), RATIO_SCALE, RoundingMode.HALF_EVEN));
    }

    /** The base currency first, always; then the others present, in ISO code order. */
    private static List<Currency> inReportOrder(Currency base, Set<Currency> present) {
        Set<String> others = new TreeSet<>();
        for (Currency currency : present) {
            if (!currency.equals(base)) {
                others.add(currency.getCurrencyCode());
            }
        }
        List<Currency> order = new ArrayList<>();
        order.add(base);
        for (String code : others) {
            order.add(Currency.getInstance(code));
        }
        return List.copyOf(order);
    }

    /** A top-level category being assembled: what was filed on it, and its children. */
    private static final class TopLevel {

        private final UUID id;
        private final String name;
        private final boolean archived;
        private Money direct;
        private Money directCompared;
        private final List<Assembled> children = new ArrayList<>();

        TopLevel(UUID id, String name, Boolean archived, Currency currency) {
            this.id = id;
            this.name = name;
            this.archived = Boolean.TRUE.equals(archived);
            this.direct = Money.zero(currency);
            this.directCompared = Money.zero(currency);
        }

        /** Raw signed sums, negated here: spending is positive when money left. */
        void addDirect(Money amount, Money compared) {
            direct = direct.minus(amount);
            directCompared = directCompared.minus(compared);
        }

        void addChild(Assembled child) {
            children.add(child);
        }

        Assembled assemble() {
            Money spending = direct;
            Money compared = directCompared;
            List<Assembled> shown = new ArrayList<>();
            for (Assembled child : children) {
                // Every child's figures are in the parent's, shown or not, so children plus
                // direct always equal the parent exactly.
                spending = spending.plus(child.spending());
                compared = compared.plus(child.comparedSpending());
                if (child.isShown()) {
                    shown.add(child);
                }
            }
            return new Assembled(id, name, archived, spending, compared, direct, shown);
        }
    }

    /** A row with its figures settled, before change and share are computed. */
    private record Assembled(
            UUID categoryId,
            String name,
            boolean archived,
            Money spending,
            Money comparedSpending,
            Money direct,
            List<Assembled> children) {

        /**
         * Non-zero spending in either range, or a child that has: "Dining: £0, down from £381" is
         * shown, not dropped.
         */
        boolean isShown() {
            return !spending.isZero() || !comparedSpending.isZero() || !children.isEmpty();
        }

        CategorySpending toRow(Money positiveTotal, List<CategorySpending> rows) {
            return new CategorySpending(
                    categoryId,
                    name,
                    archived,
                    spending,
                    comparedSpending,
                    change(spending, comparedSpending),
                    share(spending, positiveTotal),
                    direct,
                    rows);
        }

        @Override
        public String toString() {
            return "Assembled[categoryId=" + categoryId + "]";
        }
    }
}
