package com.budgetowl.debt.service;

import com.budgetowl.common.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One payment as the history renders it: what was paid, how it split, and <b>how the row got
 * here</b>.
 *
 * <p>The provenance is on every row so an inferred split is never shown as a confirmed one. {@code
 * interest + principal = amount} exactly, and {@code balanceAfter} is the balance that payment left
 * behind — both read from the stored row rather than recomputed, because recomputing a payment
 * against today's APR would rewrite history to agree with the present (ADR-0035).
 *
 * @param transactionId the real movement, when the household has named one. Null is the ordinary
 *     case, and a null that <em>appeared</em> means the ledger row was deleted — the payment stands
 *     and the balance has not moved
 */
public record DebtPaymentView(
        UUID id,
        UUID debtId,
        LocalDate paidDate,
        Money amount,
        Money interest,
        Money principal,
        Money balanceAfter,
        String provenance,
        UUID transactionId,
        UUID billOccurrenceId,
        String note,
        long version) {

    /** Redacted: a payment history is a behavioural record, and these are amounts. */
    @Override
    public String toString() {
        return "DebtPaymentView[id=" + id + ", paidDate=" + paidDate + "]";
    }

    /**
     * One page of a debt's history.
     *
     * <p>The page envelope {@code api-style.md} requires of every collection, and here it is a
     * threat-model control as well: a household with years of records has more payments than one
     * response should carry.
     */
    public record Page(
            List<DebtPaymentView> content, int page, int size, long totalElements, int totalPages) {

        public Page {
            content = List.copyOf(content);
        }
    }
}
