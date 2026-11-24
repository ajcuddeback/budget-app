package com.budgetowl.debt.service;

import com.budgetowl.common.Money;

/**
 * The four debt figures the Bills &amp; Income period summary carries, and the one it subtracts.
 *
 * <p>{@code left} loses <b>{@link #minimumsNotInBills()} only</b> — the minimums that the bills
 * block does not already carry — so a card's minimum is counted once. The designs' own figures show
 * the rule working: three minimums of 185, 342 and 118 total 645, and only 303 of them is new,
 * because the car loan's 342 is already one of the seven bill rows.
 *
 * <p><b>"Already a bill" means the month's bills term actually carries it</b>, not merely that the
 * debt names a bill. The caller passes in the bill ids that contributed to that term, so the two
 * halves of one subtraction read one object and the rule is true by construction rather than by two
 * services happening to agree. Three ways they did not: a <b>retired</b> bill produces no
 * occurrences; a <b>yearly</b> one produces none in eleven months out of twelve; a <b>skipped</b>
 * occurrence contributes nothing to the total. In every case the link survives, so asking the debt
 * "do you have a bill_id?" left the minimum counted in neither term and the headline rose by it
 * silently.
 *
 * <p>The link itself is explicit and never inferred: {@code debts.bill_id} names the bill, set by
 * the household. Matching a debt to a bill by name, amount or category would be guessing, which is
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
