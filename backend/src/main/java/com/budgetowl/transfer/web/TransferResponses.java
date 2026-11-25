package com.budgetowl.transfer.web;

import com.budgetowl.transaction.web.TransactionResponses.TransactionResponse;
import com.budgetowl.transfer.service.TransferView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
     * @param fromDate the outgoing leg's date; {@code toDate} the incoming leg's. Each leg keeps
     *     its own, because a pair made from two statements clears on two days (file-import.md rule
     *     12)
     * @param fromNote the outgoing leg's note; {@code toNote} the incoming leg's
     */
    public record TransferResponse(
            UUID id,
            UUID fromAccountId,
            String fromAccountName,
            UUID toAccountId,
            String toAccountName,
            LocalDate fromDate,
            LocalDate toDate,
            BigDecimal sent,
            String sentCurrency,
            BigDecimal received,
            String receivedCurrency,
            boolean crossCurrency,
            BigDecimal impliedRate,
            String fromNote,
            String toNote,
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
                    view.fromDate(),
                    view.toDate(),
                    view.sent().amount(),
                    view.sent().currency().getCurrencyCode(),
                    view.received().amount(),
                    view.received().currency().getCurrencyCode(),
                    view.crossCurrency(),
                    view.impliedRate(),
                    view.fromNote(),
                    view.toNote(),
                    view.createdAt(),
                    view.updatedAt(),
                    view.version());
        }
    }

    /** The two rows a split leaves, in the ledger's own shape, the leaving one first. */
    public record SplitResponse(List<TransactionResponse> transactions) {

        public SplitResponse {
            transactions = List.copyOf(transactions);
        }
    }
}
