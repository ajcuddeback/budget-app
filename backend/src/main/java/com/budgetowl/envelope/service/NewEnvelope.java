package com.budgetowl.envelope.service;

import java.time.YearMonth;
import java.util.UUID;

/**
 * An envelope to create.
 *
 * @param period the month it is for. A label, not a boundary (ADR-0031)
 * @param amount a decimal STRING (ADR-0006), in the household's base currency. Zero is allowed —
 *     deciding to spend nothing on something is a real decision
 * @param categoryId null for an envelope with no category
 */
public record NewEnvelope(String name, YearMonth period, String amount, UUID categoryId) {}
