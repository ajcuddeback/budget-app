package com.budgetowl.debt.service;

import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * How long the debt has left: <b>one unknown with two faces</b> (docs/features/debt-plan.md).
 *
 * <p>{@code monthsLeft} and {@code payoffMonth} are not two fields. They are the same quantity said
 * two ways, and the thing that converts between them is the month the projection's first period
 * starts in — the debt's next payment date, which {@link DebtTerms#firstPaymentMonth()} carries.
 * Holding them as two independent fields is how a payoff date and a month count come to disagree,
 * which is why neither {@code DebtTerms} nor the database row does so.
 *
 * <p>A payoff date derived from monthly arithmetic cannot be day-accurate, so the second face is a
 * {@link YearMonth} and never a {@link java.time.LocalDate}. Day-precision deltas are false
 * precision.
 *
 * @param monthsLeft the number of monthly periods, counting the first payment as month 1. Always at
 *     least 1 — a horizon of zero months is a paid-off debt, not a projection
 */
public record Horizon(int monthsLeft) {

    public Horizon {
        if (monthsLeft < 1) {
            throw new IllegalArgumentException(
                    "A horizon is at least one month, not " + monthsLeft);
        }
    }

    public static Horizon ofMonths(int monthsLeft) {
        return new Horizon(monthsLeft);
    }

    /**
     * The same horizon expressed as its other face: <em>"I want this gone by Dec 2027"</em>.
     *
     * @param payoffMonth the month the last payment falls in, inclusive. {@code firstPaymentMonth}
     *     itself is a one-month horizon, not a zero-month one
     * @throws IllegalArgumentException if {@code payoffMonth} is before {@code firstPaymentMonth}.
     *     A payoff month in the past is unsolvable rather than malformed where it arrives from a
     *     request, so the web edge checks it before building a {@code Horizon} — see {@link
     *     DebtTermsSolver}
     */
    public static Horizon endingIn(YearMonth payoffMonth, YearMonth firstPaymentMonth) {
        long months = ChronoUnit.MONTHS.between(firstPaymentMonth, payoffMonth);
        if (months < 0) {
            throw new IllegalArgumentException(
                    "A payoff month of "
                            + payoffMonth
                            + " is before the first payment month of "
                            + firstPaymentMonth);
        }
        return new Horizon(Math.toIntExact(months) + 1);
    }

    /** The month the last payment falls in, given the month the first one does. */
    public YearMonth payoffMonth(YearMonth firstPaymentMonth) {
        return firstPaymentMonth.plusMonths(monthsLeft - 1L);
    }
}
