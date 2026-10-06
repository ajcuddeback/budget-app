package com.budgetowl.auth.web;

import com.budgetowl.auth.domain.CredentialTransport;
import com.budgetowl.household.domain.HouseholdRole;
import java.time.Instant;
import java.util.UUID;

/**
 * Response bodies for the authentication endpoints.
 *
 * <p><b>Read the component lists rather than trusting an annotation.</b> None of these records has
 * a field that could hold a password hash, a token hash or a session id — the guarantee that a
 * credential cannot be serialized out of this API is in the types, and a test asserts it against
 * the raw JSON rather than the DTO.
 */
public final class AuthResponses {

    private AuthResponses() {}

    /**
     * @param household null for a signed-in user with no membership — an OIDC-provisioned user
     *     before anyone invited them. An empty state, not an error.
     */
    public record CurrentUserResponse(
            UUID id,
            String email,
            String displayName,
            boolean instanceAdmin,
            MembershipResponse household) {}

    public record MembershipResponse(
            UUID householdId,
            String name,
            String baseCurrency,
            HouseholdRole role,
            UUID membershipId,
            String displayCurrency,
            String locale) {}

    /**
     * The one response in the application that carries a credential, because there is no other way
     * to give a device its token. It is never stored in this form and never returned again.
     *
     * @param token shown exactly once
     */
    public record TokenResponse(
            UUID deviceId, String token, String deviceLabel, Instant expiresAt) {

        /** Redacted: a record's generated {@code toString} would print the token. */
        @Override
        public String toString() {
            return "TokenResponse[deviceId=" + deviceId + ", expiresAt=" + expiresAt + "]";
        }
    }

    /**
     * @param id a handle, not a credential: a row id for a token, and a SHA-256 for a session,
     *     because the session id <em>is</em> the cookie
     */
    public record DeviceResponse(
            String id,
            CredentialTransport kind,
            String label,
            Instant createdAt,
            Instant lastUsedAt,
            Instant expiresAt,
            boolean current) {}
}
