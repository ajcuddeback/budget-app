package com.budgetowl.admin.web;

import com.budgetowl.admin.service.InstanceView;
import com.budgetowl.admin.service.SettingsView;
import com.budgetowl.auth.domain.UserStatus;
import com.budgetowl.auth.domain.UserSummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response bodies for the admin endpoints.
 *
 * <p><b>There is no field anywhere here for the OIDC client secret.</b> Not masked, not partially
 * shown — absent. A secret that is read back lands in a browser cache, a screenshot, an HTTP log
 * and a bug report, and an administrator who cannot remember theirs replaces it.
 *
 * <p>And no financial data: not a balance, not a transaction, not a count of them.
 */
public final class AdminResponses {

    private AdminResponses() {}

    /** What the operator needs to know about their server. */
    public record InstanceResponse(
            String version,
            Instant setupCompletedAt,
            UUID administratorId,
            String administratorEmail,
            String administratorName,
            long accountCount) {

        static InstanceResponse of(InstanceView view) {
            return new InstanceResponse(
                    view.version(),
                    view.setupCompletedAt(),
                    view.administratorId(),
                    view.administratorEmail(),
                    view.administratorName(),
                    view.accountCount());
        }
    }

    /**
     * How this instance lets people sign in.
     *
     * @param oidcClientSecretSet whether one is configured. The only thing said about it
     * @param passwordLoginLocked whether the switch cannot be used yet, so the console can state
     *     the rule beside it instead of offering a control that fails
     */
    public record SettingsResponse(
            boolean registrationOpen,
            boolean passwordLoginEnabled,
            boolean passwordLoginLocked,
            boolean oidcEnabled,
            String oidcIssuerUri,
            String oidcClientId,
            boolean oidcClientSecretSet,
            boolean oidcProvisioningEnabled,
            Instant oidcOwnerLoginAt,
            Instant updatedAt,
            long version) {

        static SettingsResponse of(SettingsView view) {
            return new SettingsResponse(
                    view.registrationOpen(),
                    view.passwordLoginEnabled(),
                    view.passwordLoginLocked(),
                    view.oidcEnabled(),
                    view.oidcIssuerUri(),
                    view.oidcClientId(),
                    view.oidcClientSecretSet(),
                    view.oidcProvisioningEnabled(),
                    view.oidcOwnerLoginAt(),
                    view.updatedAt(),
                    view.version());
        }
    }

    /** One account that can sign in. No financial data, and no password hash to have one. */
    public record AccountResponse(
            UUID id,
            String email,
            String displayName,
            UserStatus status,
            boolean instanceAdmin,
            Instant createdAt) {

        /** Redacted: an email address is a personal detail, whoever is reading the log. */
        @Override
        public String toString() {
            return "AccountResponse[id=" + id + ", status=" + status + "]";
        }

        static AccountResponse of(UserSummary summary) {
            return new AccountResponse(
                    summary.id(),
                    summary.email(),
                    summary.displayName(),
                    summary.status(),
                    summary.instanceAdmin(),
                    summary.createdAt());
        }
    }

    /** The accounts, with the count a client would otherwise compute and get wrong. */
    public record AccountsResponse(List<AccountResponse> content, int total) {

        static AccountsResponse of(List<UserSummary> summaries) {
            List<AccountResponse> rows = summaries.stream().map(AccountResponse::of).toList();
            return new AccountsResponse(rows, rows.size());
        }
    }
}
