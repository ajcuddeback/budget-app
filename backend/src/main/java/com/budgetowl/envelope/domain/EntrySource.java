package com.budgetowl.envelope.domain;

/**
 * Where an envelope entry's figure came from.
 *
 * <p><b>A person decided, either way.</b> {@link #SYNCED} does not mean the app matched a
 * transaction on its own — {@code docs/features/envelopes.md} is explicit that a transaction never
 * files itself, however confident the category match looks. It means somebody attached a
 * transaction they had already recorded.
 *
 * <p>The distinction is shown on every row of the log, because a figure you typed and a figure
 * taken from a transaction are different kinds of claim and must never be presented identically.
 */
public enum EntrySource {

    /** Typed. The primary path, and the one that works with nothing connected to anything. */
    MANUAL,

    /** Attached by a person to a transaction they had already recorded. */
    SYNCED
}
