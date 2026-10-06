package com.budgetowl.household.service;

import com.budgetowl.household.domain.HouseholdRole;
import java.time.Instant;
import java.util.UUID;

/**
 * A new invitation, with the token that goes in the link — <b>the only time it exists</b>. What is
 * stored is its SHA-256, so the server cannot reproduce this value later and an owner who loses the
 * link creates a new invitation rather than recovering the old one.
 *
 * <p>The link itself is composed by the client from its own origin. The server does not build it:
 * the only origin available here is the {@code Host} header, which the caller controls, and a
 * credential-bearing URL assembled from an attacker-supplied host is a phishing link with our
 * signature on it.
 *
 * @param token the credential, shown exactly once
 */
public record CreatedInvitation(
        UUID id, String email, HouseholdRole role, String token, Instant expiresAt) {

    /** Redacted: a record's generated {@code toString} would print the token. */
    @Override
    public String toString() {
        return "CreatedInvitation[id=" + id + ", role=" + role + ", expiresAt=" + expiresAt + "]";
    }
}
