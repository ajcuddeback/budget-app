package com.budgetowl.auth.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the logged-in-devices view.
 *
 * <p>No component can hold a token hash, so the devices screen cannot leak one however it is
 * serialized. That is the whole reason this record exists instead of returning {@link AuthToken}.
 */
public record AuthTokenSummary(
        UUID id,
        String deviceLabel,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt,
        Instant revokedAt) {}
