package com.budgetowl.debt.service;

import com.budgetowl.common.Money;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a month-by-month projection into the line a chart draws, <b>bounded independently of the
 * solver's iteration cap</b>.
 *
 * <p>This is a threat-model control (docs/features/debt-plan.md, Security considerations): the
 * projection may iterate {@link DebtTermsSolver#MAX_MONTHS} months internally, and a response that
 * carried a point for every one of them — per debt, per strategy — would be an expensive {@code
 * GET} any caller could ask for. Beyond the cap the line is <b>thinned</b> rather than truncated: a
 * truncated line would show a fifty-year plan clearing in five years, which is worse than a coarse
 * one. A number a human reads off a chart does not need monthly resolution to year 50.
 *
 * <p>A pure function: no clock, no household, no database.
 */
final class BalanceSeries {

    private BalanceSeries() {}

    /**
     * What is still owed at the <b>start</b> of each month, with a final point where the line
     * lands.
     *
     * <p>The last value is the closing balance of the last month, clamped at zero: a final payment
     * that overshoots has not left the household owing a negative amount, it has finished. Without
     * that point the line stops at the last month's opening balance and never reaches the axis —
     * <em>"$26,480 → $0"</em> would end above zero.
     */
    static List<Money> openings(List<ProjectionMonth> months) {
        List<Money> balances = new ArrayList<>(months.size() + 1);
        for (ProjectionMonth month : months) {
            balances.add(month.balance());
        }
        Money closing = months.get(months.size() - 1).balanceAfter();
        balances.add(closing.isPositive() ? closing : Money.zero(closing.currency()));
        return balances;
    }

    /**
     * One point per month, thinned to {@code cap} points.
     *
     * <p>Every {@code stride}th month is kept, and the last point always is: a chart whose line
     * stops one stride short of zero reads as a plan that does not finish.
     *
     * @param firstMonth the month the first value belongs to
     * @param cap the most points the response may carry. At least 2
     */
    static Series of(YearMonth firstMonth, List<Money> balances, int cap) {
        if (balances.isEmpty()) {
            return new Series(List.of(), 1);
        }
        int stride = Math.max(1, (balances.size() + cap - 1) / cap);
        List<PlanView.SeriesPoint> points = new ArrayList<>(cap + 1);
        for (int index = 0; index < balances.size(); index += stride) {
            points.add(new PlanView.SeriesPoint(firstMonth.plusMonths(index), balances.get(index)));
        }
        int last = balances.size() - 1;
        if (last % stride != 0) {
            points.add(new PlanView.SeriesPoint(firstMonth.plusMonths(last), balances.get(last)));
        }
        return new Series(List.copyOf(points), stride);
    }

    /**
     * A bounded line, and how many months one of its points stands for.
     *
     * <p>The stride travels with the points because a client that assumed "one point, one month"
     * would mislabel the axis of exactly the long plan the cap exists for.
     */
    record Series(List<PlanView.SeriesPoint> points, int stride) {

        static Series empty() {
            return new Series(List.of(), 1);
        }

        Series {
            points = List.copyOf(points);
        }
    }
}
