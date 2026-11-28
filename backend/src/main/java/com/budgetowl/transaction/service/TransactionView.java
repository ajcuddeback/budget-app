package com.budgetowl.transaction.service;

import com.budgetowl.common.Money;
import com.budgetowl.transaction.domain.TransactionStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One transaction, with the names a ledger row needs to render without a second round trip.
 *
 * <p>The names are resolved in a batch per page, never per row. A ledger is the screen a person
 * looks at most, and one query per row for the account, the category and the payee is three N+1s on
 * the hot path.
 *
 * @param amount signed, in the account's currency — negative is money leaving
 * @param categoryId null means "Needs a category": a real state the UI shows, not missing data
 * @param categoryParentName null when the category is top-level or absent; lets the client render
 *     {@code Bills → Utilities} without looking the parent up
 * @param transferGroupId the pair this leg belongs to, or null. <b>A client needs it to act on the
 *     transfer</b>: a transfer is addressed by its group id, so without this a ledger row could
 *     show a transfer and offer nothing to do with it — the id is not derivable from the leg, and
 *     guessing one would edit somebody else's pair
 * @param transfer true when this is one half of a transfer (slice 8). Reporting must not count it
 *     as income or expense
 */
public record TransactionView(
        UUID id,
        UUID accountId,
        String accountName,
        LocalDate date,
        Money amount,
        UUID payeeId,
        String payeeName,
        UUID categoryId,
        String categoryName,
        String categoryParentName,
        String note,
        TransactionStatus status,
        UUID transferGroupId,
        boolean transfer,
        Instant createdAt,
        Instant updatedAt,
        long version) {}
