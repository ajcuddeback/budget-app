package com.budgetowl.admin.web;

import com.budgetowl.common.Change;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request bodies for the admin endpoints. Entities are never request bodies.
 *
 * <p><b>The client secret goes in and never comes out.</b> This is the only shape in the codebase
 * that accepts one, and {@code AdminResponses} has no field for it — so the asymmetry is visible in
 * the types rather than maintained by care.
 *
 * <p>{@code toString} is redacted, and here that is not a precaution: Spring MVC logs the
 * deserialized body at {@code DEBUG}, and the generated one would print an OIDC client secret into
 * the log of every instance whose operator was debugging a login problem — which is exactly when
 * they would have turned {@code DEBUG} on.
 */
public final class AdminRequests {

    private AdminRequests() {}

    /** A partial change. A class with setters, so an absent secret and a cleared one differ. */
    public static final class UpdateSettingsRequest {

        private Boolean registrationOpen;

        private Boolean passwordLoginEnabled;

        private Boolean oidcEnabled;

        @Size(max = 500)
        private String oidcIssuerUri;

        @Size(max = 200)
        private String oidcClientId;

        @Size(max = 500)
        private String oidcClientSecret;

        private boolean secretMentioned;

        private Boolean oidcProvisioningEnabled;

        /** Required: optimistic locking only works if the caller says which version it read. */
        @NotNull private Long version;

        public void setRegistrationOpen(Boolean registrationOpen) {
            this.registrationOpen = registrationOpen;
        }

        public void setPasswordLoginEnabled(Boolean passwordLoginEnabled) {
            this.passwordLoginEnabled = passwordLoginEnabled;
        }

        public void setOidcEnabled(Boolean oidcEnabled) {
            this.oidcEnabled = oidcEnabled;
        }

        public void setOidcIssuerUri(String oidcIssuerUri) {
            this.oidcIssuerUri = oidcIssuerUri;
        }

        public void setOidcClientId(String oidcClientId) {
            this.oidcClientId = oidcClientId;
        }

        /** Called by Jackson only when the property is present — that is the whole mechanism. */
        public void setOidcClientSecret(String oidcClientSecret) {
            this.oidcClientSecret = oidcClientSecret;
            this.secretMentioned = true;
        }

        public void setOidcProvisioningEnabled(Boolean oidcProvisioningEnabled) {
            this.oidcProvisioningEnabled = oidcProvisioningEnabled;
        }

        public void setVersion(Long version) {
            this.version = version;
        }

        public Boolean registrationOpen() {
            return registrationOpen;
        }

        public Boolean passwordLoginEnabled() {
            return passwordLoginEnabled;
        }

        public Boolean oidcEnabled() {
            return oidcEnabled;
        }

        public String oidcIssuerUri() {
            return oidcIssuerUri;
        }

        public String oidcClientId() {
            return oidcClientId;
        }

        public Boolean oidcProvisioningEnabled() {
            return oidcProvisioningEnabled;
        }

        public Long version() {
            return version;
        }

        /** A blank secret clears it rather than storing one made of spaces. */
        public Change<String> oidcClientSecretChange() {
            if (!secretMentioned) {
                return Change.unchanged();
            }
            return oidcClientSecret == null || oidcClientSecret.isBlank()
                    ? Change.cleared()
                    : Change.setTo(oidcClientSecret);
        }

        /** Redacted — see the class Javadoc. It says whether a secret was sent, never what. */
        @Override
        public String toString() {
            return "UpdateSettingsRequest[version="
                    + version
                    + ", secretSent="
                    + secretMentioned
                    + "]";
        }
    }
}
