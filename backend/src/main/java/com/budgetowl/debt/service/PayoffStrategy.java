package com.budgetowl.debt.service;

/**
 * The order debts are attacked in (docs/features/debt-plan.md).
 *
 * <p><b>Both named strategies are always computed, whichever one is selected</b> — the comparison
 * card is a product requirement, not a view of the selection. The designs refuse to pick for the
 * household: <em>"Avalanche costs $780 less. Snowball clears the Visa three months sooner, which
 * some people need to keep going. Either is a good answer."</em>
 *
 * <p>Ordering is <b>total and deterministic, including on ties</b>, or the ladder reshuffles
 * between two reads of the same data. {@link PayoffLadder#rank} is where each order is written out.
 */
public enum PayoffStrategy {

    /** Highest APR first: the cheapest order, and the one that saves interest. */
    AVALANCHE,

    /** Smallest balance first: the fastest first win, which is snowball's whole argument. */
    SNOWBALL,

    /** The household's own order, with anything unordered falling back to {@link #AVALANCHE}. */
    CUSTOM
}
