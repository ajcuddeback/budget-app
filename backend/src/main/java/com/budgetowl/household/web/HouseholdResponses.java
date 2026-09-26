package com.budgetowl.household.web;

import com.budgetowl.household.domain.HouseholdRole;
import java.time.Instant;
import java.util.UUID;

/**
 * Response bodies for the household endpoints.
 *
 * <p>No component here can hold a token, a hash or an invitation link. The invitation list — which
 * this slice does not expose — could not leak one either: the token is not recoverable from the
 * database at all.
 */
public final class HouseholdResponses {

    private HouseholdResponses() {}

    /**
     * @param role the caller's own role, so a client never has to ask twice to render a screen
     */
    public record HouseholdResponse(
            UUID id, String name, String baseCurrency, HouseholdRole role) {}

    public record MemberResponse(
            UUID id,
            UUID userId,
            String email,
            String displayName,
            HouseholdRole role,
            Instant joinedAt,
            String displayCurrency,
            String locale) {}

    /**
     * A new invitation.
     *
     * <p>The token is here once and never again — what is stored is its SHA-256. The absolute link
     * is composed by the client from its own origin; {@code acceptPath} is the relative half. The
     * server does not build the URL because the only origin it has is the {@code Host} header,
     * which the caller sets, and a credential-bearing link assembled from an attacker's host is a
     * phishing link with our name on it.
     *
     * @param token shown exactly once
     */
    public record InvitationResponse(
            UUID id,
            String email,
            HouseholdRole role,
            String token,
            String acceptPath,
            Instant expiresAt) {

        /** Redacted: a record's generated {@code toString} would print the token. */
        @Override
        public String toString() {
            return "InvitationResponse[id="
                    + id
                    + ", role="
                    + role
                    + ", expiresAt="
                    + expiresAt
                    + "]";
        }
    }

    /**
     * What a joiner is told. No credential comes back: accepting a link proves possession of the
     * link, not of the account, so they sign in afterwards like anybody else.
     */
    public record AcceptInvitationResponse(
            UUID householdId, String householdName, HouseholdRole role, boolean userCreated) {}
}
