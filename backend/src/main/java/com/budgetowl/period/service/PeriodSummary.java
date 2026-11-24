package com.budgetowl.period.service;

import com.budgetowl.common.Money;
import java.time.YearMonth;

/**
 * What a household has left to spend in one month.
 *
 * <p><b>One subtraction, and the page leads with it:</b> {@code left = income − bills − envelopes −
 * debtMinimumsNotInBills}. The four terms are reported alongside so the figure can be explained
 * without a second request, and so a household that disagrees with it can see which term it
 * disagrees with.
 *
 * <p><b>The fourth term arrived with slice 10</b>, and it changed a figure that had already shipped
 * (the repository owner's decision, recorded in {@code docs/features/debt-plan.md}). It is the
 * minimums that are <b>not</b> already carried by a bill row, so a card's minimum is counted once:
 * the designs' own figures are minimums of 185, 342 and 118, of which only 185 + 118 = 303 is new,
 * because the car loan's 342 is already one of the seven bill rows.
 *
 * @param income what the active sources imply for the month. An expectation, not money that
 *     arrived: only a real transaction is ever income (ADR-0025)
 * @param bills the month's bills in all, <b>paid ones included</b>, at what was really paid where
 *     that is known. Rent that has already gone is not money still available
 * @param billsOutstanding the part of {@code bills} not yet settled. The secondary line
 * @param billsOverdue the part of {@code billsOutstanding} whose due date has passed
 * @param envelopesAllocated what the month's envelopes set aside — <b>allocated, not spent</b>.
 *     Deciding that £400 is for groceries takes it out of what is free to spend elsewhere whether
 *     or not any of it has gone yet
 * @param owed the total debt balance — the <b>Owed</b> card. Not part of the subtraction: it is a
 *     balance, not a monthly commitment, and subtracting it would say a household owing £26,000 has
 *     nothing to spend this month
 * @param debtCount how many debts are active, for the card's own line
 * @param debtMinimums every active debt's minimum, added up. Reported so the counted-once rule is
 *     visible: this figure minus {@code debtMinimumsNotInBills} is what the bills block already
 *     carries
 * @param debtMinimumsNotInBills the only debt figure this subtraction uses
 * @param left the headline. It <b>goes negative</b>, and a client must render that rather than
 *     clamping it: "you are £180 short this month" is the information
 */
public record PeriodSummary(
        YearMonth period,
        Money income,
        Money bills,
        Money billsOutstanding,
        Money billsOverdue,
        Money envelopesAllocated,
        Money owed,
        int debtCount,
        Money debtMinimums,
        Money debtMinimumsNotInBills,
        Money left) {

    /** Redacted: a generated one is a household's whole month, in one line. */
    @Override
    public String toString() {
        return "PeriodSummary[period=" + period + "]";
    }
}
