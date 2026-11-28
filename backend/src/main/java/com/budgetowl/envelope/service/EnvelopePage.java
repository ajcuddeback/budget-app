package com.budgetowl.envelope.service;

import com.budgetowl.common.Money;
import java.util.List;

/**
 * One period's envelopes, with the period's own totals.
 *
 * <p>The totals are safe to compute across envelopes here where they are not across accounts
 * (ADR-0022): every envelope in a household is denominated in that household's base currency, so
 * there is exactly one currency involved and nothing is being converted.
 */
public record EnvelopePage(
        List<EnvelopeView> envelopes,
        Money totalAllocated,
        Money totalSpent,
        Money totalRemaining,
        int page,
        int size,
        long totalElements,
        int totalPages) {}
