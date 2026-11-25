package com.budgetowl.report.web;

import com.budgetowl.common.Money;
import com.budgetowl.report.service.IncomeAndSpendingReport;
import com.budgetowl.report.service.SpendingByCategoryReport;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What the two report endpoints send.
 *
 * <p><b>Every amount is a string at scale 4</b> inside an object that names its currency (ADR-0006,
 * {@code api-style.md}): never a JSON number, never formatted, never converted. Ratios ({@code
 * change}, {@code share}) are strings at scale 4 as well, because the client formats them with
 * {@code Intl} and does no arithmetic of its own.
 *
 * <p><b>These are documents, not collections</b>, so they carry arrays without a page envelope —
 * bounded by the 24-month span, the 200-category cap and the household's currencies, like {@code
 * /api/debt-plan}. A page boundary in the middle of a report would make its totals unreadable.
 *
 * <p><b>Every record prints its shape and never a figure or a name.</b> Spring MVC logs the
 * returned body's {@code toString} at {@code DEBUG} (docs/features/reporting.md, attack 6).
 */
public final class ReportResponses {

    private ReportResponses() {}

    private static String amount(Money money) {
        return money.amount().toPlainString();
    }

    private static String ratio(BigDecimal ratio) {
        return ratio.toPlainString();
    }

    // ------------------------------------------------------------------------------- report 1

    /** Report 1. */
    public record IncomeAndSpendingResponse(
            String from,
            String to,
            String baseCurrency,
            List<CurrencyIncomeAndSpendingResponse> currencies) {

        static IncomeAndSpendingResponse of(IncomeAndSpendingReport report) {
            return new IncomeAndSpendingResponse(
                    report.range().from().toString(),
                    report.range().to().toString(),
                    report.baseCurrency().getCurrencyCode(),
                    report.currencies().stream()
                            .map(CurrencyIncomeAndSpendingResponse::of)
                            .toList());
        }

        @Override
        public String toString() {
            return "IncomeAndSpendingResponse[from="
                    + from
                    + ", to="
                    + to
                    + ", currencies="
                    + currencies.size()
                    + "]";
        }
    }

    /**
     * One currency's months.
     *
     * @param averageKept null when no month in the range is complete — never {@code "0.0000"}
     * @param bestMonth null when no month in the range is complete
     * @param transactionCount every non-transfer row in the range; zero is the empty state
     */
    public record CurrencyIncomeAndSpendingResponse(
            String currency,
            List<MonthResponse> months,
            TotalsResponse totals,
            String averageKept,
            BestMonthResponse bestMonth,
            int completeMonths,
            long uncategorisedCount,
            long transactionCount) {

        static CurrencyIncomeAndSpendingResponse of(IncomeAndSpendingReport.CurrencyFigures c) {
            return new CurrencyIncomeAndSpendingResponse(
                    c.currency().getCurrencyCode(),
                    c.months().stream().map(MonthResponse::of).toList(),
                    TotalsResponse.of(c.totals()),
                    c.averageKept().map(ReportResponses::amount).orElse(null),
                    c.bestMonth().map(BestMonthResponse::of).orElse(null),
                    c.completeMonths(),
                    c.uncategorisedCount(),
                    c.transactionCount());
        }

        @Override
        public String toString() {
            return "CurrencyIncomeAndSpendingResponse[currency="
                    + currency
                    + ", months="
                    + months.size()
                    + "]";
        }
    }

    /** One month. {@code spending} is negative, not zero, when refunds beat spending. */
    public record MonthResponse(
            String period,
            String income,
            String spending,
            String uncategorisedIn,
            String uncategorisedOut,
            String kept,
            boolean complete) {

        static MonthResponse of(IncomeAndSpendingReport.MonthFigures month) {
            return new MonthResponse(
                    month.period().toString(),
                    amount(month.income()),
                    amount(month.spending()),
                    amount(month.uncategorisedIn()),
                    amount(month.uncategorisedOut()),
                    amount(month.kept()),
                    month.complete());
        }

        @Override
        public String toString() {
            return "MonthResponse[period=" + period + ", complete=" + complete + "]";
        }
    }

    /** The range's figures summed by the server, the in-progress month included. */
    public record TotalsResponse(
            String income,
            String spending,
            String uncategorisedIn,
            String uncategorisedOut,
            String kept) {

        static TotalsResponse of(IncomeAndSpendingReport.Totals totals) {
            return new TotalsResponse(
                    amount(totals.income()),
                    amount(totals.spending()),
                    amount(totals.uncategorisedIn()),
                    amount(totals.uncategorisedOut()),
                    amount(totals.kept()));
        }

        @Override
        public String toString() {
            return "TotalsResponse[]";
        }
    }

    /** The complete month that kept the most, the earliest on a tie. */
    public record BestMonthResponse(String period, String kept) {

        static BestMonthResponse of(IncomeAndSpendingReport.BestMonth best) {
            return new BestMonthResponse(best.period().toString(), amount(best.kept()));
        }

        @Override
        public String toString() {
            return "BestMonthResponse[period=" + period + "]";
        }
    }

    // ------------------------------------------------------------------------------- report 2

    /**
     * Report 2.
     *
     * @param complete false when the range reaches the current month, by the server's clock
     */
    public record SpendingByCategoryResponse(
            String from,
            String to,
            String comparedFrom,
            String comparedTo,
            boolean complete,
            String baseCurrency,
            List<CurrencySpendingResponse> currencies) {

        static SpendingByCategoryResponse of(SpendingByCategoryReport report) {
            return new SpendingByCategoryResponse(
                    report.range().from().toString(),
                    report.range().to().toString(),
                    report.compared().from().toString(),
                    report.compared().to().toString(),
                    report.complete(),
                    report.baseCurrency().getCurrencyCode(),
                    report.currencies().stream().map(CurrencySpendingResponse::of).toList());
        }

        @Override
        public String toString() {
            return "SpendingByCategoryResponse[from="
                    + from
                    + ", to="
                    + to
                    + ", currencies="
                    + currencies.size()
                    + "]";
        }
    }

    /** One currency's category tree, and the uncategorised outflow beside it. */
    public record CurrencySpendingResponse(
            String currency,
            String spending,
            String comparedSpending,
            List<CategorySpendingResponse> categories,
            UncategorisedResponse uncategorised) {

        static CurrencySpendingResponse of(SpendingByCategoryReport.CurrencySpending c) {
            return new CurrencySpendingResponse(
                    c.currency().getCurrencyCode(),
                    amount(c.spending()),
                    amount(c.comparedSpending()),
                    c.categories().stream().map(CategorySpendingResponse::of).toList(),
                    new UncategorisedResponse(
                            amount(c.uncategorised().spending()), c.uncategorised().count()));
        }

        @Override
        public String toString() {
            return "CurrencySpendingResponse[currency="
                    + currency
                    + ", categories="
                    + categories.size()
                    + "]";
        }
    }

    /**
     * One category row.
     *
     * @param change null when {@code comparedSpending} is zero
     * @param share null when {@code spending} is zero or negative
     * @param direct what was filed on this category itself; for a child, its whole spending
     */
    public record CategorySpendingResponse(
            UUID categoryId,
            String name,
            boolean archived,
            String spending,
            String comparedSpending,
            String change,
            String share,
            String direct,
            List<CategorySpendingResponse> children) {

        static CategorySpendingResponse of(SpendingByCategoryReport.CategorySpending row) {
            return new CategorySpendingResponse(
                    row.categoryId(),
                    row.name(),
                    row.archived(),
                    amount(row.spending()),
                    amount(row.comparedSpending()),
                    row.change().map(ReportResponses::ratio).orElse(null),
                    row.share().map(ReportResponses::ratio).orElse(null),
                    amount(row.direct()),
                    row.children().stream().map(CategorySpendingResponse::of).toList());
        }

        /** The id only. A household's category name is the disclosing half of this row. */
        @Override
        public String toString() {
            return "CategorySpendingResponse[categoryId=" + categoryId + "]";
        }
    }

    /** The range's uncategorised outflow: not a category, so not in the tree. */
    public record UncategorisedResponse(String spending, long count) {

        @Override
        public String toString() {
            return "UncategorisedResponse[count=" + count + "]";
        }
    }
}
