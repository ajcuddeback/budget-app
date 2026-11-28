package com.budgetowl.income.domain;

/**
 * Where an income figure came from.
 *
 * <p>Only {@link #MANUAL_ESTIMATE} is written until reporting exists (slice 13). A number derived
 * from six months of history means something different from one somebody typed, and the record has
 * to say which — the same principle as a bill occurrence's status source and an envelope entry's.
 */
public enum SourceOfTruth {

    /** Somebody typed it. */
    MANUAL_ESTIMATE,

    /** Averaged from history, with the window part of the record. Nothing writes this yet. */
    AUTO
}
