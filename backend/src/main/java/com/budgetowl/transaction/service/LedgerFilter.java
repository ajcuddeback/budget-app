package com.budgetowl.transaction.service;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What to narrow the ledger by. Every field is optional; all null is "everything, newest first".
 *
 * @param uncategorisedOnly the one filter that is not simply a value: a null {@code categoryId}
 *     already means "any category", so "has no category" needs its own flag. It is also the filter
 *     a person actually acts on — the work queue that turns a pile of transactions into a budget
 * @param search matched case-insensitively against the note and the payee's name
 */
public record LedgerFilter(
        UUID accountId,
        UUID categoryId,
        boolean uncategorisedOnly,
        LocalDate from,
        LocalDate to,
        String search) {

    /**
     * The open lower and upper bounds, used when the caller named no date range.
     *
     * <p>Real dates rather than nulls because the query cannot take a null date bound at all —
     * {@code TransactionRepository}'s class note has the reason. They are far outside any range a
     * household will record, and well inside what a PostgreSQL {@code date} can hold.
     */
    public static final LocalDate EARLIEST = LocalDate.of(1, 1, 1);

    public static final LocalDate LATEST = LocalDate.of(9999, 12, 31);

    public static LedgerFilter everything() {
        return new LedgerFilter(null, null, false, null, null, null);
    }

    /** The lower bound to query with: what the caller asked for, or the open bound. */
    public LocalDate fromOrEarliest() {
        return from == null ? EARLIEST : from;
    }

    public LocalDate toOrLatest() {
        return to == null ? LATEST : to;
    }
}
