package com.budgetowl.debt.service;

import com.budgetowl.common.Money;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * A solved term set: the four quantities, which one was derived, and the ledger they imply.
 *
 * @param solvedField {@code apr}, {@code payment} or {@code horizon} — the cell the UI labels
 *     {@code Solved}, and no other
 * @param reason {@code never-clears} where the payment does not cover the first month's interest.
 *     The term set is still a legitimate answer: the balance grows, there is no horizon, and the
 *     screen says it in the arithmetic's own terms rather than refusing the numbers
 * @param bisectionSteps how many times the recurrence was probed. Reported because the bound on it
 *     is a threat-model control, and a control that is not observable is a control nobody can test
 */
public record SolvedTermsView(
        Money balance,
        BigDecimal apr,
        Money payment,
        Integer monthsLeft,
        YearMonth payoffMonth,
        Money totalInterest,
        String solvedField,
        String reason,
        List<PlanView.SeriesPoint> series,
        int seriesMonthStride,
        int bisectionSteps) {

    public SolvedTermsView {
        series = List.copyOf(series);
    }

    /** Redacted — every figure in here is somebody's debt. */
    @Override
    public String toString() {
        return "SolvedTermsView[solvedField=" + solvedField + ", reason=" + reason + "]";
    }
}
