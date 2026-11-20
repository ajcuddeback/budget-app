package com.budgetowl.envelope.service;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Something to log against an envelope.
 *
 * <p><b>Exactly one of {@code amount} and {@code transactionId}.</b> An amount is a typed ({@code
 * MANUAL}) entry. A transaction id attaches a transaction the person already recorded ({@code
 * SYNCED}), and the figure then comes from the transaction rather than from the caller — which is
 * what makes the two agree, and what makes the entry worth marking as synced at all.
 *
 * @param amount a positive decimal STRING, or null when attaching a transaction
 * @param date null means today for a typed entry, and the transaction's own date when attaching
 * @param payeeName optional, and created on first use like a transaction's
 */
public record NewEntry(
        String amount, UUID transactionId, LocalDate date, String payeeName, String note) {}
