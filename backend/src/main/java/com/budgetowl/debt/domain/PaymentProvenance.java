package com.budgetowl.debt.domain;

/**
 * How a payment row got here, exactly as {@code StatusSource} does for a bill occurrence
 * (docs/features/debt-plan.md).
 *
 * <p>Slice 10 writes {@link #USER_LOGGED} and {@link #STATEMENT_CORRECTION} only.
 */
public enum PaymentProvenance {

    /** Somebody recorded it. The slice-10 path, and the one the payment sheet writes. */
    USER_LOGGED,

    /**
     * Matched against a real charge on a connected account. <b>Not reachable in slice 10</b> (slice
     * 16); the value exists now so that a figure somebody asserted never becomes indistinguishable
     * from one the app inferred.
     */
    AUTO_MATCHED,

    /**
     * The arithmetic overruled by a statement — <em>"Statement corrected +$4.10"</em>.
     *
     * <p>Routinely runs the other way to a payment: a correction that raises the balance is a
     * negative amount, which is why no sign constraint exists on these columns.
     */
    STATEMENT_CORRECTION
}
