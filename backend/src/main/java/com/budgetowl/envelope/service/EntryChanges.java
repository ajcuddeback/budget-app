package com.budgetowl.envelope.service;

import com.budgetowl.common.Change;
import java.time.LocalDate;

/**
 * A partial update to a log entry. A null {@code date} or {@code amount} was not mentioned.
 *
 * <p>There is no {@code envelopeId} and no {@code transactionId}: moving an entry changes two
 * remaining figures at once, and re-pointing the transaction would make the entry's figure disagree
 * with the one it claims to have come from. Both are a delete and a re-log.
 */
public record EntryChanges(
        LocalDate date,
        String amount,
        Change<String> payeeName,
        Change<String> note,
        long version) {}
