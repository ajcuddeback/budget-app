package com.budgetowl.auth.web;

import com.budgetowl.auth.service.PasswordPolicy;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request bodies for the authentication endpoints. */
public final class AuthRequests {

    private AuthRequests() {}

    /**
     * Note what is <em>not</em> validated: the password has no minimum length here. A login is not
     * the place to enforce a policy — refusing a short password with a different status than a
     * wrong one would tell an attacker their guess was too short to be anybody's, and users whose
     * password predates a policy change still have to be able to sign in.
     */
    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = PasswordPolicy.MAXIMUM_BYTES) String password) {

        /**
         * Redacted, all of it.
         *
         * <p>Spring MVC logs the deserialized request body at DEBUG ("Read ... to
         * [LoginRequest...]"), and a record's generated toString prints every component — which on
         * this endpoint means a password in a log file. Emails are PII and a target list, so they
         * do not go in either.
         */
        @Override
        public String toString() {
            return "LoginRequest[redacted]";
        }
    }

    /**
     * @param deviceLabel the user's own words for the phone, shown on their devices screen so they
     *     can tell which one to revoke
     */
    public record IssueTokenRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = PasswordPolicy.MAXIMUM_BYTES) String password,
            @NotBlank @Size(max = 100) String deviceLabel) {

        /**
         * Redacted, all of it.
         *
         * <p>Spring MVC logs the deserialized request body at DEBUG ("Read ... to
         * [IssueTokenRequest...]"), and a record's generated toString prints every component —
         * which on this endpoint means a password in a log file. Emails are PII and a target list,
         * so they do not go in either.
         */
        @Override
        public String toString() {
            return "IssueTokenRequest[redacted]";
        }
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = PasswordPolicy.MAXIMUM_BYTES) String currentPassword,
            @NotBlank @Size(min = PasswordPolicy.MINIMUM_LENGTH, max = PasswordPolicy.MAXIMUM_BYTES)
                    String newPassword) {

        /**
         * Redacted, all of it.
         *
         * <p>Spring MVC logs the deserialized request body at DEBUG ("Read ... to
         * [ChangePasswordRequest...]"), and a record's generated toString prints every component —
         * which on this endpoint means a password in a log file. Emails are PII and a target list,
         * so they do not go in either.
         */
        @Override
        public String toString() {
            return "ChangePasswordRequest[redacted]";
        }
    }
}
