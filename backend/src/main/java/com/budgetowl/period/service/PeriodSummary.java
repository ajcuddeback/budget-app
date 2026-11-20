package com.budgetowl.period.service;

import com.budgetowl.common.Money;
import java.time.YearMonth;

/**
 * What a household has left to spend in one month.
 *
 * <p><b>One subtraction, and the page leads with it:</b> {@code left = income − bills − envelopes}.
 * The three terms are reported alongside so the figure can be explained without a second request,
 * and so a household that disagrees with it can see which term it disagrees with.
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
        Money left) {

    /** Redacted: a generated one is a household's whole month, in one line. */
    @Override
    public String toString() {
        return "PeriodSummary[period=" + period + "]";
    }
}
