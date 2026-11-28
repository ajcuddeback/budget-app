package com.budgetowl.debt.domain;

/**
 * Where a debt's balance came from, and <b>the UI never renders two of them identically</b>
 * (docs/features/debt-plan.md).
 *
 * <p>The same discipline {@code StatusSource} gives a bill occurrence: a figure somebody asserted
 * must never become indistinguishable from one the app worked out.
 *
 * <p>The stored column holds the <b>anchor's</b> provenance. What a read reports can differ: an
 * anchor the household confirmed in June, read in September with nothing logged since, is reported
 * {@link #ESTIMATED} because that is what the figure on screen is.
 */
public enum BalanceSource {

    /** An anchor the household entered or confirmed, with the date it was true. */
    CONFIRMED,

    /**
     * The anchor rolled forward over months with no logged payment, assuming the minimum was paid
     * <b>and the interest in between</b> — an estimate is not the anchor minus the payments.
     *
     * <p>Capped at {@code DebtBalanceService.MAXIMUM_ESTIMATED_MONTHS} months; past that the debt
     * reports its last confirmed balance and that date instead of a year of guesses.
     */
    ESTIMATED,

    /**
     * From a connected account. <b>Not reachable in slice 10</b> (slice 16) — the value is legal
     * from day one so nothing has to be backfilled, exactly as {@code AUTO_MATCHED} is.
     */
    LINKED_STATEMENT
}
