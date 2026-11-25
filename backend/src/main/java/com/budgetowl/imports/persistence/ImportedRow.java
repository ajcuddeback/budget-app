package com.budgetowl.imports.persistence;

import java.util.UUID;

/** A ledger row that some earlier import wrote, by its {@code external_id}. */
public record ImportedRow(UUID transactionId, String externalId) {}
