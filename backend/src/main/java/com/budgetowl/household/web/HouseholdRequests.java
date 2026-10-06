package com.budgetowl.household.web;

import com.budgetowl.auth.service.PasswordPolicy;
import com.budgetowl.household.domain.HouseholdRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request bodies for the household endpoints. */
public final class HouseholdRequests {

    private HouseholdRequests() {}

    /**
     * @param baseCurrency ISO 4217, re-checked against the JVM's currency table in the service
     */
    public record UpdateHouseholdRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String baseCurrency) {}

    /**
     * @param role the new role. Who may set it is a service decision — only an {@code OWNER}, and
     *     never on themselves.
     */
    public record ChangeMemberRoleRequest(@NotNull HouseholdRole role) {}

    /**
     * A member's own preferences. Both {@code null} means "follow the household and the platform",
     * which is a meaning rather than a missing value (ADR-0022, ADR-0023).
     */
    public record UpdateOwnMembershipRequest(
            @Pattern(regexp = "^[A-Za-z]{3}$") String displayCurrency,
            @Size(max = 35) @Pattern(regexp = "^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")
                    String locale) {}

    /**
     * @param email a label on the invitation, not an authorization: possession of the link is what
     *     grants access, which is why the link is treated as a credential
     */
    public record CreateInvitationRequest(
            @NotBlank @Email @Size(max = 254) String email, @NotNull HouseholdRole role) {}

    /**
     * Accepting a link. Both fields are needed only when the invited address has no user yet — an
     * existing user is simply added to the household.
     */
    public record AcceptInvitationRequest(
            @Size(max = 100) String displayName,
            @Size(min = PasswordPolicy.MINIMUM_LENGTH, max = PasswordPolicy.MAXIMUM_BYTES)
                    String password) {

        /**
         * Redacted, all of it.
         *
         * <p>Spring MVC logs the deserialized request body at DEBUG ("Read ... to
         * [AcceptInvitationRequest...]"), and a record's generated toString prints every component
         * — which on this endpoint means a password in a log file. Emails are PII and a target
         * list, so they do not go in either.
         */
        @Override
        public String toString() {
            return "AcceptInvitationRequest[redacted]";
        }
    }
}
