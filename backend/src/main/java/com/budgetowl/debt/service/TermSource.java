package com.budgetowl.debt.service;

/**
 * Where a cell in the <em>Loan terms</em> panel got its figure.
 *
 * <p><b>Three labels, not two</b> (docs/features/debt-plan.md). That is what makes the designs' own
 * count — <em>"4 entered · 3 solved"</em> over eight cells — add up: the eighth cell is the payment
 * the plan assigned, which is neither something the household typed nor something the solver worked
 * out from their terms.
 *
 * <p>The distinction is the whole point of the panel: a figure the app derived must never be
 * mistaken for one the household asserted. It is the same discipline {@code statusSource} gives a
 * bill occurrence and {@code balanceSource} gives a balance, applied to arithmetic.
 */
public enum TermSource {

    /** The household typed it. Stored, and never replaced by a computed value. */
    ENTERED,

    /** The solver derived it from the other three. {@code NULL} in the row. */
    SOLVED,

    /** The payoff plan allocated it: this debt's minimum, plus the surplus if it is rung 1. */
    FROM_PLAN
}
