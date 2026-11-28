package com.budgetowl.admin.service;

import java.time.Instant;
import java.util.UUID;

/**
 * What the operator needs to know about their server.
 *
 * <p><b>No financial data</b>, here or anywhere else in this package. ADR-0026 already says the
 * operator can read the database if they choose — it is their machine — and that is exactly why
 * this must not become the screen that does it for them.
 *
 * @param administratorId and {@code administratorEmail}: who holds the flag. Exactly one person
 *     does (ADR-0029), and an operator who cannot see which account it is cannot act on it
 * @param accountCount how many people can sign in. A count, not a ledger
 */
public record InstanceView(
        String version,
        Instant setupCompletedAt,
        UUID administratorId,
        String administratorEmail,
        String administratorName,
        long accountCount) {}
