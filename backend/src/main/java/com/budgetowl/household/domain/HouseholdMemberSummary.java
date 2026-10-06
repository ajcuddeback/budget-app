package com.budgetowl.household.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the members list, assembled by a single join rather than a query per member.
 *
 * <p>Carries the user's email and display name because the members screen needs them, and nothing
 * else about the user — there is no component that could hold a password hash.
 */
public record HouseholdMemberSummary(
        UUID id,
        UUID householdId,
        UUID userId,
        String email,
        String displayName,
        HouseholdRole role,
        Instant joinedAt,
        String displayCurrency,
        String locale) {}
