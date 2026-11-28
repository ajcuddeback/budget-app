package com.budgetowl.debt.service;

import java.time.LocalDate;

/**
 * A statement figure: what the lender says is owed, and the date it was true.
 *
 * <p><b>A statement wins.</b> Where the figure disagrees with the arithmetic, the difference is
 * recorded as a {@code STATEMENT_CORRECTION} payment row — <em>"Statement corrected +$4.10"</em> —
 * and the anchor moves to this figure. History is never silently adjusted to agree with the
 * present, which is why the correction is a row rather than an edit.
 *
 * <p>This is also the route that <b>reopens a settled debt</b>: writes to a paid-off debt are
 * refused, and a correction is how a household says the balance came back.
 *
 * @param balance a decimal string (ADR-0006). Zero is how a debt is paid off and a negative figure
 *     is an overpayment the screen has to be able to show, so neither is refused here
 * @param balanceDate the date the statement says it was true. Never in the future: a balance
 *     confirmed for next month is not a confirmation
 */
public record BalanceConfirmation(String balance, LocalDate balanceDate, String note) {

    /** Redacted: the balance is what somebody owes. */
    @Override
    public String toString() {
        return "BalanceConfirmation[balanceDate=" + balanceDate + "]";
    }
}
