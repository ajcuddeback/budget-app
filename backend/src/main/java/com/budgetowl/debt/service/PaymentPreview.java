package com.budgetowl.debt.service;

import com.budgetowl.common.Money;

/**
 * What logging a payment would record, before it is recorded.
 *
 * <p>Read off the very row {@code DebtPaymentService.log} would save, so it is the same split and
 * the same closing balance the household gets once it commits — not an estimate of them. {@code
 * interest + principal = amount} exactly, for the same reason the stored row's does.
 *
 * @param interest what this payment would pay in interest, rounded once, half-even, at scale 4
 * @param principal the rest of the payment: what it would take off the balance
 * @param balanceBefore what is owed now — the balance the split is taken against
 * @param balanceAfter what would be owed once it is recorded. Can go below zero, which the screen
 *     says is an overpayment rather than clamping it
 */
public record PaymentPreview(
        Money interest, Money principal, Money balanceBefore, Money balanceAfter) {

    /** Redacted: four amounts, and two of them are what this household owes. */
    @Override
    public String toString() {
        return "PaymentPreview[]";
    }
}
