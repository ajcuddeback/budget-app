package com.budgetowl.debt.service;

import com.budgetowl.common.Money;

/**
 * The four debt figures the Bills &amp; Income period summary carries, and the one it subtracts.
 *
 * <p>{@code left} loses <b>{@link #minimumsNotInBills()} only</b> — the minimums that are not
 * already a bill row — so a card's minimum is counted once. The designs' own figures show the rule
 * working: three minimums of 185, 342 and 118 total 645, and only 303 of them is new, because the
 * car loan's 342 is already one of the seven bill rows.
 *
 * <p>The link is explicit and never inferred: {@code debts.bill_id} names the bill, set by the
 * household. Matching a debt to a bill by name, amount or category would be guessing, which is
 * precisely why bills refuse to match themselves to envelopes that way.
 *
 * @param owed the total balance, in the household's base currency only (ADR-0022)
 * @param count how many debts are active, across every currency
 */
public record DebtFigures(Money owed, int count, Money minimums, Money minimumsNotInBills) {

    /** Redacted: four amounts, and one of them is everything the household owes. */
    @Override
    public String toString() {
        return "DebtFigures[count=" + count + "]";
    }
}
