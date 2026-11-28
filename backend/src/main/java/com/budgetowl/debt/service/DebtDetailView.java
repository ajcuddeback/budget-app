package com.budgetowl.debt.service;

import java.util.List;

/**
 * One debt's own page: its terms, and the two lines the chart draws.
 *
 * @param pace what the debt does at what it is actually paying — the plan's allocation, or the
 *     payment the household entered
 * @param atMinimum what it would do at its minimum, which is the <em>"if you'd paid the
 *     minimum"</em> comparison line. Empty when the minimum never clears the balance, which is the
 *     common real case for a card and not an error
 * @param seriesMonthStride how many months one point of either line stands for. Both lines share
 *     it, so they plot against one axis
 * @param atMinimumIsTheSameLine true when the debt is already paying exactly its minimum, so the
 *     two lines coincide. Reported rather than left for a client to discover by comparing arrays —
 *     <em>"paying $260 extra is buying you 27 months"</em> is a sentence with nothing in it when
 *     there is no extra
 */
public record DebtDetailView(
        DebtView debt,
        List<PlanView.SeriesPoint> pace,
        List<PlanView.SeriesPoint> atMinimum,
        int seriesMonthStride,
        boolean atMinimumIsTheSameLine) {

    public DebtDetailView {
        pace = List.copyOf(pace);
        atMinimum = List.copyOf(atMinimum);
    }

    /** Redacted — see {@code DebtView}. */
    @Override
    public String toString() {
        return "DebtDetailView[id=" + debt.id() + "]";
    }
}
