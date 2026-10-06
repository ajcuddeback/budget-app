package com.budgetowl.auth.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Everything about a user that may safely leave the persistence layer.
 *
 * <p>A projection rather than an entity, and one with no component for a password hash — so a query
 * that tried to select one would not compile. Read the class list, not an annotation, to know what
 * can escape.
 */
public record UserSummary(
        UUID id,
        String email,
        String displayName,
        UserStatus status,
        boolean instanceAdmin,
        Instant createdAt) {}
