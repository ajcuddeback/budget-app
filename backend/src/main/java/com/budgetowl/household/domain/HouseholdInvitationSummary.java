package com.budgetowl.household.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the owner's invitations list.
 *
 * <p>No component can hold the token or its hash, so the list cannot hand back a working invitation
 * link however it is serialized. Re-sharing a link means the owner kept it; the server cannot
 * reproduce it.
 */
public record HouseholdInvitationSummary(
        UUID id,
        String email,
        HouseholdRole role,
        Instant expiresAt,
        Instant acceptedAt,
        Instant revokedAt,
        Instant createdAt) {}
