package com.budgetowl.debt.service;

import com.budgetowl.common.Money;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * The payoff plan as a screen reads it: the two figures the household chose, and everything derived
 * from them.
 *
 * <p><b>Grouped by currency</b>, because the plan's totals are per currency (ADR-0022). A household
 * with debts in two currencies gets no single debt-free date and no combined total interest — those
 * would need a rate the instance may not have, and a converted figure is never the input to stored
 * arithmetic. The grouping says so rather than inventing one, the same way the ledger returns a
 * null page total when a page mixes currencies.
 *
 * @param strategy the selection. <b>Both named strategies are projected whichever one is
 *     selected</b> — the comparison card is a product requirement, not a view of the selection
 * @param monthlyAmount what the household put towards debt, in its base currency
 * @param minimums the sum of every ranked debt's minimum, in the base currency. The plan header's
 *     two terms are this and {@code monthlyAmount}; the designs' own figures do not reconcile, so
 *     the API defines them and the screen does not copy the numbers
 * @param debtCount how many debts are ranked. Zero is the empty state, and the screen says what the
 *     page is for rather than rendering a chart of nothing and a debt-free date of today
 * @param groups one per currency, the household's base currency first
 * @param version the plan's own, for {@code PUT}. Two members changing strategy collide on it
 */
public record PlanView(
        String strategy,
        Money monthlyAmount,
        Money minimums,
        int debtCount,
        List<CurrencyGroup> groups,
        long version) {

    public PlanView {
        groups = List.copyOf(groups);
    }

    /** True when the plan spans more than one currency, which is what makes the totals partial. */
    public boolean isMixedCurrency() {
        return groups.size() > 1;
    }

    /**
     * One currency's ladder.
     *
     * @param monthlyAmount what <b>this group's</b> projection assumed. For the plan's own currency
     *     that is the household's monthly amount; for any other it is the sum of that group's
     *     minimums, because the surplus exists in one currency and cannot be allocated in another
     *     without a rate (ADR-0022). The field is reported so the assumption is visible rather than
     *     mysterious
     * @param belowMinimums the monthly amount is less than the minimums. <b>Accepted, not
     *     refused</b> — a household that genuinely cannot meet its minimums exists, and {@code 422}
     *     is the wrong thing to say to them — and no projection is computed, because every figure
     *     from such an amount would be a fiction
     * @param selected the full ladder for the selected strategy: this is where the rungs are
     * @param avalanche and {@code snowball}: the comparison, with <b>no rungs</b>. Both carry their
     *     own series so the chart can draw two lines from one request
     */
    public record CurrencyGroup(
            String currency,
            boolean planCurrency,
            Money monthlyAmount,
            Money minimums,
            Money surplus,
            Money owed,
            boolean belowMinimums,
            StrategyProjection selected,
            StrategyProjection avalanche,
            StrategyProjection snowball) {

        /**
         * Redacted, like its two siblings in this file.
         *
         * <p>The generated {@code toString} prints every component, and {@code Money.toString}
         * prints the figure — so the default here is five amounts, one of which ({@code owed}) is
         * everything the household owes in this currency. Nothing logs it today; the control is
         * that the next {@code log.debug("plan={}", group)} or interpolated exception message
         * cannot leak one by accident (docs/features/debt-plan.md, controls).
         */
        @Override
        public String toString() {
            return "CurrencyGroup[currency=" + currency + ", planCurrency=" + planCurrency + "]";
        }
    }

    /**
     * One strategy's answer.
     *
     * @param debtFreeMonth the latest of the per-debt payoff months, or null when at least one debt
     *     does not clear within the cap. Null rather than a date that ignores the underwater debt
     * @param firstWinMonth when rung 1 clears. Reported on its own because it is snowball's whole
     *     argument
     * @param reason why there is no projection, where there is none: {@code plan-below-minimums},
     *     or {@code never-clears} for a ladder where a debt's payment does not cover its interest
     * @param rungs the order and the per-debt allocation. <b>Empty on the two comparison
     *     projections</b>: the table is drawn for the selected strategy, and shipping every rung
     *     three times would carry two copies nothing renders
     * @param series the balance-to-zero line, one point per month, <b>bounded independently of the
     *     solver's 600-month iteration cap</b> (docs/features/debt-plan.md). A number a human reads
     *     off a chart does not need monthly resolution to year 50
     * @param seriesMonthStride how many months one point stands for: 1 for a plan that clears
     *     inside the cap, more for one that runs long. Reported so a client labels the axis
     *     correctly rather than assuming months
     */
    public record StrategyProjection(
            String strategy,
            YearMonth debtFreeMonth,
            YearMonth firstWinMonth,
            Money totalInterest,
            String reason,
            List<Rung> rungs,
            List<SeriesPoint> series,
            int seriesMonthStride) {

        public StrategyProjection {
            rungs = List.copyOf(rungs);
            series = List.copyOf(series);
        }

        /** Redacted: {@code totalInterest} is an amount, and the series is a balance per month. */
        @Override
        public String toString() {
            return "StrategyProjection[strategy="
                    + strategy
                    + ", debtFreeMonth="
                    + debtFreeMonth
                    + ", reason="
                    + reason
                    + ", rungs="
                    + rungs.size()
                    + ", series="
                    + series.size()
                    + "]";
        }
    }

    /** One debt's place in the ladder, with the allocation the plan makes it in the first month. */
    public record Rung(
            int rung,
            UUID debtId,
            String name,
            String kind,
            String servicer,
            Money balance,
            java.math.BigDecimal apr,
            boolean aprDerivable,
            Money minimum,
            Money extra,
            Money monthlyAmount,
            YearMonth payoffMonth,
            Money totalInterest) {

        /** Redacted: a rung is a name, a balance and a rate in one line. */
        @Override
        public String toString() {
            return "Rung[rung=" + rung + ", debtId=" + debtId + "]";
        }
    }

    /** One point on the chart: what is still owed at the start of that month. */
    public record SeriesPoint(YearMonth month, Money balance) {

        @Override
        public String toString() {
            return "SeriesPoint[month=" + month + "]";
        }
    }

    /** Redacted: a generated one is the household's whole financial position in a line. */
    @Override
    public String toString() {
        return "PlanView[strategy=" + strategy + ", debtCount=" + debtCount + "]";
    }
}
