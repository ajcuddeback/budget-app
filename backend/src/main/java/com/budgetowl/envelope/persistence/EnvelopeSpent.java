package com.budgetowl.envelope.persistence;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One envelope's spend so far, and how many entries make it up.
 *
 * <p>A constructor-expression projection rather than {@code Object[]}: the column order cannot be
 * misread at the call site, and adding a column is a compile error rather than an index that
 * silently means something else.
 *
 * @param spent the sum of the envelope's entries. Never null for a row that appears at all, because
 *     an envelope with no entries produces no group
 */
public record EnvelopeSpent(UUID envelopeId, BigDecimal spent, long entryCount) {}
