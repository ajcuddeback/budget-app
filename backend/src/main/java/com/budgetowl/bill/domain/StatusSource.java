package com.budgetowl.bill.domain;

/**
 * How an occurrence's status was reached.
 *
 * <p>Always {@link #MANUAL} until bank connections exist (slice 16). The field is here now so that
 * a figure the household asserted never becomes indistinguishable from one the app inferred — which
 * is the entire reason ADR-0025 asks for it, and the same principle as an envelope entry's source.
 */
public enum StatusSource {

    /** Somebody said so. The primary path: it needs no connection, no import and no match. */
    MANUAL,

    /** A confident {@code ChargeRule} match set it. Nothing writes this yet. */
    AUTO_MATCHED
}
