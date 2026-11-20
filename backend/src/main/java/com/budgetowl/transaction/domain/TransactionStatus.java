package com.budgetowl.transaction.domain;

/**
 * How settled a transaction is.
 *
 * <p>Stored as a string and constrained by {@code ck_transactions_status}. A persisted ordinal
 * would renumber every row the day a fourth status is inserted anywhere but the end.
 *
 * <p><b>With no bank connection, everything a person types is {@link #CLEARED}.</b> That is not a
 * shortcut: the person entering it watched it happen and is the authority on whether it did. {@link
 * #PENDING} exists for the case they want to record something they expect to settle — a cheque in
 * the post, a card authorisation — and for imports (slice 12) whose source says so.
 */
public enum TransactionStatus {

    /** Entered, but not yet confirmed to have settled. Still counts towards the balance. */
    PENDING,

    /** Confirmed. The default for anything a person enters by hand. */
    CLEARED,

    /**
     * Part of a statement period that has been reconciled and closed (slice 13). Nothing sets this
     * yet, and reconciliation is what will — not a user toggling it.
     */
    RECONCILED
}
