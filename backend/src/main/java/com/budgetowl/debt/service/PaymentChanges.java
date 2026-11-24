package com.budgetowl.debt.service;

import com.budgetowl.common.Change;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A correction to a payment somebody recorded.
 *
 * <p><b>The interest is not recomputed.</b> Correcting a mistyped figure is the household saying
 * what they paid, not that the rate was different — so the stored interest stands, the principal
 * takes the difference, and the split still reconciles exactly. Recomputing it against today's APR
 * would rewrite history to agree with the present, which is the one thing an audit trail must not
 * do (ADR-0035).
 *
 * <p>A household editing its own payment record is not an attack; it is the feature.
 *
 * @param transactionId three-way: unchanged, cleared, or linked to a transaction this household
 *     owns. Clearing it is how somebody undoes a wrong link without deleting the payment
 */
public record PaymentChanges(
        LocalDate paidDate,
        String amount,
        Change<UUID> transactionId,
        Change<String> note,
        long version) {

    /** Redacted — see {@code NewDebtPayment}. */
    @Override
    public String toString() {
        return "PaymentChanges[version=" + version + "]";
    }
}
