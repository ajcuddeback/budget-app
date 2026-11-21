package com.budgetowl.transfer.web;

import com.budgetowl.transfer.service.TransferView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Response bodies for the transfer endpoints. The entity never appears in one. */
public final class TransferResponses {

    private TransferResponses() {}

    /**
     * One transfer, read as the single thing it is.
     *
     * @param id the transfer's group id — the pair is the object, so neither leg's id names it
     * @param sent what left, positive, in {@code sentCurrency}
     * @param received what arrived, in {@code receivedCurrency}. The same magnitude as {@code sent}
     *     when the currencies match
     * @param crossCurrency whether the two accounts are denominated differently. A client needs
     *     this to decide whether printing one figure would be honest
     * @param impliedRate {@code received / sent}, or null when the currencies match. <b>Derived for
     *     display</b> and stored nowhere (ADR-0022) — it is not what the bank charged, and nothing
     *     reads it back
     */
    public record TransferResponse(
            UUID id,
            UUID fromAccountId,
            String fromAccountName,
            UUID toAccountId,
            String toAccountName,
            LocalDate date,
            BigDecimal sent,
            String sentCurrency,
            BigDecimal received,
            String receivedCurrency,
            boolean crossCurrency,
            BigDecimal impliedRate,
            String note,
            Instant createdAt,
            Instant updatedAt,
            long version) {

        /** Redacted — a generated one carries both figures and the note. */
        @Override
        public String toString() {
            return "TransferResponse[id=" + id + ", crossCurrency=" + crossCurrency + "]";
        }

        public static TransferResponse of(TransferView view) {
            return new TransferResponse(
                    view.id(),
                    view.fromAccountId(),
                    view.fromAccountName(),
                    view.toAccountId(),
                    view.toAccountName(),
                    view.date(),
                    view.sent().amount(),
                    view.sent().currency().getCurrencyCode(),
                    view.received().amount(),
                    view.received().currency().getCurrencyCode(),
                    view.crossCurrency(),
                    view.impliedRate(),
                    view.note(),
                    view.createdAt(),
                    view.updatedAt(),
                    view.version());
        }
    }
}
