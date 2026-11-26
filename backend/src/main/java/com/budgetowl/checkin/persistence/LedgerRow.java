package com.budgetowl.checkin.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One transaction as a check-in reads it: enough to ask about it, and nothing it could write
 * through. A constructor-expression projection, so it is not a managed entity.
 *
 * @param attached whether a bill occurrence, a debt payment, a goal contribution or an envelope
 *     entry already points at it. An attached row is never offered for attaching and never counts
 *     as unbudgeted spending
 */
public record LedgerRow(
        UUID id,
        UUID accountId,
        String accountName,
        String accountLastFour,
        String currency,
        LocalDate date,
        BigDecimal amount,
        UUID payeeId,
        String payeeName,
        UUID categoryId,
        boolean transfer,
        long version,
        boolean attached) {

    public boolean isOutflow() {
        return amount.signum() < 0;
    }

    /** Redacted: a payee, an amount and a date are a line of somebody's statement. */
    @Override
    public String toString() {
        return "LedgerRow[id=" + id + "]";
    }
}
