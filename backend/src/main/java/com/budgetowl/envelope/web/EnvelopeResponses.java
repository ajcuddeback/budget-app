package com.budgetowl.envelope.web;

import com.budgetowl.envelope.domain.EntrySource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response bodies for the envelope endpoints. The entity never appears in one. */
public final class EnvelopeResponses {

    private EnvelopeResponses() {}

    /**
     * One envelope.
     *
     * @param remaining <b>the headline</b>: {@code amount} minus {@code spent}, computed on the
     *     server and stored nowhere. It is NEGATIVE for an overspent envelope, and a client must
     *     render that rather than clamping it — "you are £40 over" is the information
     * @param empty whether anything has been logged at all. Not derivable from {@code spent} being
     *     zero without knowing that entries are always positive
     * @param period the month, as {@code 2026-08}. A label, not a boundary (ADR-0031)
     */
    public record EnvelopeResponse(
            UUID id,
            String name,
            String period,
            UUID categoryId,
            String categoryName,
            String categoryParentName,
            BigDecimal amount,
            BigDecimal spent,
            BigDecimal remaining,
            String currency,
            long entryCount,
            boolean empty,
            boolean archived,
            Instant createdAt,
            Instant updatedAt,
            long version) {

        /** Redacted: a generated one would put the household's plan and spend into any log line. */
        @Override
        public String toString() {
            return "EnvelopeResponse[id=" + id + ", period=" + period + "]";
        }
    }

    /**
     * One period's envelopes with its own totals.
     *
     * <p>Totalling across envelopes is safe where totalling across accounts is not (ADR-0022):
     * every envelope in a household shares the household's base currency, so nothing is converted.
     */
    public record EnvelopePageResponse(
            List<EnvelopeResponse> content,
            BigDecimal totalAllocated,
            BigDecimal totalSpent,
            BigDecimal totalRemaining,
            String currency,
            int page,
            int size,
            long totalElements,
            int totalPages) {

        /** Redacted, for the reason {@code LedgerResponse} is. */
        @Override
        public String toString() {
            return "EnvelopePageResponse[page=" + page + ", totalElements=" + totalElements + "]";
        }
    }

    /**
     * One row of an envelope's log.
     *
     * @param source shown on every row: a figure somebody typed and one taken from a transaction
     *     are different kinds of claim
     */
    public record EntryResponse(
            UUID id,
            UUID envelopeId,
            LocalDate date,
            BigDecimal amount,
            String currency,
            UUID payeeId,
            String payeeName,
            String note,
            EntrySource source,
            UUID transactionId,
            Instant createdAt,
            Instant updatedAt,
            long version) {

        /** Redacted — amounts, payees and notes are financial data. */
        @Override
        public String toString() {
            return "EntryResponse[id=" + id + ", source=" + source + "]";
        }
    }
}
