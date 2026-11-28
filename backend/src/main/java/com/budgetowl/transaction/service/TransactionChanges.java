package com.budgetowl.transaction.service;

import com.budgetowl.common.Change;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A partial update. A null {@code date}, {@code amount} or {@code status} was not mentioned.
 *
 * <p>Payee, category and note are {@link Change}s rather than nullable values, because for all
 * three "not mentioned" and "clear it" are different instructions — and a client that PATCHed an
 * amount and silently cleared the note would be losing data the user typed.
 *
 * <p><b>There is no {@code accountId}.</b> Moving a transaction between accounts changes two
 * balances and, when the currencies differ, the meaning of the amount. It is not an edit, so the
 * contract cannot express it: the user deletes and re-enters, or records a transfer (slice 8).
 */
public record TransactionChanges(
        LocalDate date,
        String amount,
        Change<String> payeeName,
        Change<UUID> categoryId,
        Change<String> note,
        String status,
        long version) {}
