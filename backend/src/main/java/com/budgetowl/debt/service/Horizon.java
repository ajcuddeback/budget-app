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
     * Whether a payoff month names a horizon the solver could ever answer about.
     *
     * <p>Exists so the two callers that build a {@code Horizon} from a request — the stateless
     * solver and the debt writer — share one definition instead of each checking one side of it.
     * The old code bounded {@code monthsLeft} above and below and bounded {@code payoffMonth} only
     * from below, so {@code "payoffMonth":"+999999999-12"} parsed, reached {@link
     * ChronoUnit#MONTHS} and overflowed {@code Math.toIntExact} — eleven billion months, a {@code
     * 500} and a stack trace where the contract promises a {@code 422}.
     *
     * <p>Bounding it here rather than at each caller closes the class rather than one instance:
     * anything outside the window is a well-formed request with no answer in it, which is what
     * {@code terms-unsolvable} means.
     */
    public static boolean isSolvableSpan(YearMonth payoffMonth, YearMonth firstPaymentMonth) {
        long months = ChronoUnit.MONTHS.between(firstPaymentMonth, payoffMonth);
        return months >= 0 && months < DebtTermsSolver.MAX_MONTHS;
    }

    /**
     * The same horizon expressed as its other face: <em>"I want this gone by Dec 2027"</em>.
     *
     * @param payoffMonth the month the last payment falls in, inclusive. {@code firstPaymentMonth}
     *     itself is a one-month horizon, not a zero-month one
     * @throws IllegalArgumentException if {@code payoffMonth} is outside {@link #isSolvableSpan}:
     *     before {@code firstPaymentMonth}, or further beyond it than the solver's own cap. Either
     *     is unsolvable rather than malformed where it arrives from a request, so a caller taking
     *     one from a request asks {@link #isSolvableSpan} first and answers {@code
     *     terms-unsolvable} — see {@link DebtTermsSolver}
     */
    public static Horizon endingIn(YearMonth payoffMonth, YearMonth firstPaymentMonth) {
        if (!isSolvableSpan(payoffMonth, firstPaymentMonth)) {
            // Not Math.toIntExact's ArithmeticException: that is not a DomainException, so it
            // leaves as a 500 with a stack trace. Callers taking a payoff month from a request
            // must ask isSolvableSpan first and answer 422; this is the backstop for the rest.
            throw new IllegalArgumentException(
                    "A payoff month of "
                            + payoffMonth
                            + " is not within "
                            + DebtTermsSolver.MAX_MONTHS
                            + " months of the first payment month of "
                            + firstPaymentMonth);
        }
        return new Horizon((int) ChronoUnit.MONTHS.between(firstPaymentMonth, payoffMonth) + 1);
    }

    /** The month the last payment falls in, given the month the first one does. */
    public YearMonth payoffMonth(YearMonth firstPaymentMonth) {
        return firstPaymentMonth.plusMonths(monthsLeft - 1L);
    }
}
