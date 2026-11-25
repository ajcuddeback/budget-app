package com.budgetowl.report.service;

import com.budgetowl.common.Money;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Report 1: income and spending, by month, <b>per currency</b> (ADR-0022, ADR-0039).
 *
 * <p>Every record here prints its shape and never a figure. Spring MVC logs a returned body's
 * {@code toString} at {@code DEBUG}, and a record prints every component by default — which would
 * put a household's monthly income into the log of an operator chasing an unrelated bug
 * (docs/features/reporting.md, attack 6).
 *
 * @param currencies the base currency first, always present; then any other currency with a
 *     non-transfer transaction in the range, in ISO code order
 */
public record IncomeAndSpendingReport(
        ReportRange range, Currency baseCurrency, List<CurrencyFigures> currencies) {

    public IncomeAndSpendingReport {
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(baseCurrency, "baseCurrency");
        currencies = List.copyOf(currencies);
    }

    @Override
    public String toString() {
        return "IncomeAndSpendingReport[range="
                + range.from()
                + ".."
                + range.to()
                + ", currencies="
                + currencies.size()
                + "]";
    }

    /**
     * One currency's complete report.
     *
     * @param months one per month in the range, dense: a quiet month is zeros, not missing
     * @param averageKept the mean of {@code kept} over the complete months, at scale 4 {@code
     *     HALF_EVEN}; empty when no month in the range is complete
     * @param bestMonth the complete month with the highest {@code kept}, the earliest on a tie;
     *     empty when no month is complete
     * @param completeMonths how many months the two figures above were taken over
     * @param uncategorisedCount uncategorised rows in the range, in either direction
     * @param transactionCount every non-transfer row in the range. Zero is the empty state: figures
     *     that net to zero are not the same fact as nothing recorded
     */
    public record CurrencyFigures(
            Currency currency,
            List<MonthFigures> months,
            Optional<Money> averageKept,
            Optional<BestMonth> bestMonth,
            int completeMonths,
            long uncategorisedCount,
            long transactionCount) {

        public CurrencyFigures {
            Objects.requireNonNull(currency, "currency");
            months = List.copyOf(months);
            Objects.requireNonNull(averageKept, "averageKept");
            Objects.requireNonNull(bestMonth, "bestMonth");
        }

        @Override
        public String toString() {
            return "CurrencyFigures[currency=" + currency + ", months=" + months.size() + "]";
        }
    }

    /**
     * One month of one currency. Every figure is net, and the identity {@code kept = income −
     * spending + uncategorisedIn − uncategorisedOut} holds exactly — the service checks it.
     *
     * @param spending positive when money left; <b>negative, never clamped,</b> when refunds beat
     *     spending
     * @param uncategorisedOut positive: the money that left with no category
     * @param kept signed: negative means more left than arrived
     * @param complete false for the current month and any later one, by the application clock
     */
    public record MonthFigures(
            YearMonth period,
            Money income,
            Money spending,
            Money uncategorisedIn,
            Money uncategorisedOut,
            Money kept,
            boolean complete) {

        public MonthFigures {
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(income, "income");
            Objects.requireNonNull(spending, "spending");
            Objects.requireNonNull(uncategorisedIn, "uncategorisedIn");
            Objects.requireNonNull(uncategorisedOut, "uncategorisedOut");
            Objects.requireNonNull(kept, "kept");
        }

        @Override
        public String toString() {
            return "MonthFigures[period=" + period + ", complete=" + complete + "]";
        }
    }

    /** The complete month that kept the most. */
    public record BestMonth(YearMonth period, Money kept) {

        public BestMonth {
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(kept, "kept");
        }

        @Override
        public String toString() {
            return "BestMonth[period=" + period + "]";
        }
    }
}
