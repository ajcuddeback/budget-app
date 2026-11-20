package com.budgetowl.transaction.web;

import com.budgetowl.transaction.domain.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Response bodies for the transaction endpoints. The entity never appears in one. */
public final class TransactionResponses {

    private TransactionResponses() {}

    /**
     * One ledger row, carrying the names it needs to render so a client does not have to join three
     * collections to draw a list.
     *
     * <p>Names are returned alongside ids rather than instead of them: a client needs the id to
     * navigate or re-file, and the name to display. They are never pre-joined into one label —
     * {@code Bills → Utilities} and {@code name •1234} are composed by the client, because a joined
     * label cannot be re-ordered for a right-to-left locale (ADR-0023).
     *
     * @param amount a STRING on the wire, never a JSON number (ADR-0006). Signed: negative is money
     *     leaving the account
     * @param currency the account's own. Never a converted figure — conversion is display-only and
     *     never leaves the client's read path (ADR-0022)
     * @param categoryId null means "Needs a category": a real state the UI shows, not an error
     * @param transfer true for one half of a transfer; reporting must not count it as income or
     *     expense
     */
    public record TransactionResponse(
            UUID id,
            UUID accountId,
            String accountName,
            LocalDate date,
            BigDecimal amount,
            String currency,
            UUID payeeId,
            String payeeName,
            UUID categoryId,
            String categoryName,
            String categoryParentName,
            String note,
            TransactionStatus status,
            boolean transfer,
            Instant createdAt,
            Instant updatedAt,
            long version) {

        /**
         * Redacted. A generated {@code toString} would put the household's amounts, payees and
         * notes into any log line that touched a response, and MVC logs returned values at DEBUG.
         */
        @Override
        public String toString() {
            return "TransactionResponse[id=" + id + ", status=" + status + "]";
        }
    }

    /**
     * A page of the ledger with its own total.
     *
     * <p>Not {@code PageResponse} because the ledger carries one extra field, {@code pageTotal},
     * and bolting a money field onto the shared envelope would put it on every other collection in
     * the API where it means nothing. The four paging fields keep the same names and semantics
     * (docs/guides/api-style.md), so a client reads it with the same code.
     *
     * @param pageTotal the sum of this page's amounts as a string, or null when the page mixes
     *     currencies — never a converted figure
     */
    public record LedgerResponse(
            java.util.List<TransactionResponse> content,
            BigDecimal pageTotal,
            String pageTotalCurrency,
            int page,
            int size,
            long totalElements,
            int totalPages) {}
}
