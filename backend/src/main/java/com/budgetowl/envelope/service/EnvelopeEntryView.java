package com.budgetowl.envelope.service;

import com.budgetowl.common.Money;
import com.budgetowl.envelope.domain.EntrySource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of an envelope's log.
 *
 * @param amount positive — how much of the decided amount this entry used
 * @param source {@code MANUAL} or {@code SYNCED}, and <b>shown on every row</b>: a figure somebody
 *     typed and a figure taken from a transaction are different kinds of claim and must never be
 *     presented identically (docs/features/envelopes.md)
 * @param transactionId the transaction a person attached, or null. A {@code SYNCED} entry whose
 *     transaction was later deleted keeps its source and loses this — the plan does not stop being
 *     true because the record of the purchase was removed
 */
public record EnvelopeEntryView(
        UUID id,
        UUID envelopeId,
        LocalDate date,
        Money amount,
        UUID payeeId,
        String payeeName,
        String note,
        EntrySource source,
        UUID transactionId,
        Instant createdAt,
        Instant updatedAt,
        long version) {}
