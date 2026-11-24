package com.budgetowl.debt.service;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A payment to record against a debt.
 *
 * <p><b>Writes no transaction and moves no account balance</b> (ADR-0035). {@link #transactionId()}
 * names the real movement when the household already recorded it; the link is evidence, not the
 * source of truth, and it is resolved through the household before anything references it.
 *
 * @param amount a decimal string (ADR-0006). Not clamped to the balance: a payment whose principal
 *     exceeds the balance takes it below zero and the screen says the debt is overpaid by that
 *     much, because clamping would hide a real error in somebody's favour
 * @param billOccurrenceId the occurrence this payment answers, where it came from marking a linked
 *     bill paid. At most one payment per occurrence, ever
 * @param paidFromAccountId which account the money came from, when the household says. <b>Not</b>
 *     the account the debt is held at, and not a ledger write: nothing is recorded against that
 *     account. It is where a real charge will be looked for in slice 16, which is only useful if it
 *     is collected from the first payment onwards
 * @param note optional, and never a log line
 */
public record NewDebtPayment(
        LocalDate paidDate,
        String amount,
        UUID transactionId,
        UUID billOccurrenceId,
        UUID paidFromAccountId,
        String note) {

    /** Redacted: the amount is a payment somebody made. */
    @Override
    public String toString() {
        return "NewDebtPayment[paidDate=" + paidDate + "]";
    }
}
