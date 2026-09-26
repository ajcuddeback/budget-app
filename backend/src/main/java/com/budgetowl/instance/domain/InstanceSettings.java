package com.budgetowl.instance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * The runtime configuration of this instance: one row, forever.
 *
 * <p>Configured in the app by the instance administrator rather than by environment variable
 * (docs/architecture/security-model.md), because a self-hoster changing an OIDC issuer should not
 * have to edit a compose file and restart. Every column has a safe default and the row is created
 * by migration {@code V6}, so first run needs nothing beyond a database password (ADR-0016).
 *
 * <p>Two invariants here are the database's, not this class's:
 *
 * <ul>
 *   <li>{@code setup_completed_at} is claimed by a conditional UPDATE, so two simultaneous callers
 *       of {@code /api/setup/first-user} cannot both win. See {@code
 *       InstanceSettingsRepository.claimFirstUserSetup}.
 *   <li>{@code ck_instance_settings_password_login_lockout} refuses to disable password login until
 *       an {@code OWNER} has actually completed an OIDC login — a mechanism rather than a warning,
 *       so nobody can lock themselves out of their own server.
 * </ul>
 */
@Entity
@Table(name = "instance_settings")
public class InstanceSettings {

    /** There is one row and its id is 1. {@code ck_instance_settings_single_row} enforces it. */
    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private short id;

    @Column(name = "registration_open", nullable = false)
    private boolean registrationOpen;

    @Column(name = "password_login_enabled", nullable = false)
    private boolean passwordLoginEnabled;

    @Column(name = "oidc_enabled", nullable = false)
    private boolean oidcEnabled;

    @Column(name = "oidc_issuer_uri")
    private String oidcIssuerUri;

    @Column(name = "oidc_client_id")
    private String oidcClientId;

    @Column(name = "oidc_client_secret")
    private String oidcClientSecret;

    @Column(name = "oidc_provisioning_enabled", nullable = false)
    private boolean oidcProvisioningEnabled;

    @Column(name = "oidc_owner_login_at")
    private Instant oidcOwnerLoginAt;

    @Column(name = "setup_completed_at")
    private Instant setupCompletedAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;

    protected InstanceSettings() {
        // for JPA
    }

    public short id() {
        return id;
    }

    public boolean isRegistrationOpen() {
        return registrationOpen;
    }

    public boolean isPasswordLoginEnabled() {
        return passwordLoginEnabled;
    }

    public boolean isOidcEnabled() {
        return oidcEnabled;
    }

    public String oidcIssuerUri() {
        return oidcIssuerUri;
    }

    public String oidcClientId() {
        return oidcClientId;
    }

    public boolean isOidcProvisioningEnabled() {
        return oidcProvisioningEnabled;
    }

    public Instant oidcOwnerLoginAt() {
        return oidcOwnerLoginAt;
    }

    public Instant setupCompletedAt() {
        return setupCompletedAt;
    }

    public boolean isSetupComplete() {
        return setupCompletedAt != null;
    }

    public void openRegistration() {
        this.registrationOpen = true;
    }

    public void closeRegistration() {
        this.registrationOpen = false;
    }

    /**
     * Refused by {@code ck_instance_settings_password_login_lockout} until an {@code OWNER} has
     * signed in through OIDC. The database says no even if a service forgets to.
     */
    public void disablePasswordLogin() {
        this.passwordLoginEnabled = false;
    }

    public void enablePasswordLogin() {
        this.passwordLoginEnabled = true;
    }

    public void recordOwnerOidcLoginAt(Instant when) {
        this.oidcOwnerLoginAt = Objects.requireNonNull(when, "when");
    }

    public void configureOidc(String issuerUri, String clientId, String clientSecret) {
        this.oidcIssuerUri = Objects.requireNonNull(issuerUri, "issuerUri");
        this.oidcClientId = Objects.requireNonNull(clientId, "clientId");
        this.oidcClientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
        this.oidcEnabled = true;
    }

    public void disableOidc() {
        this.oidcEnabled = false;
        this.oidcProvisioningEnabled = false;
    }

    /** An OIDC login may provision a user; it never grants household membership. */
    public void allowOidcProvisioning(boolean allowed) {
        this.oidcProvisioningEnabled = allowed;
    }

    /**
     * The configured client secret. Deliberately not a getter-shaped name and not part of any
     * projection: it is a credential the operator typed in, and it leaves this class only on the
     * way to the OIDC client.
     */
    public String oidcClientSecretForClientConfiguration() {
        return oidcClientSecret;
    }

    @Override
    public String toString() {
        return "InstanceSettings[registrationOpen="
                + registrationOpen
                + ", passwordLoginEnabled="
                + passwordLoginEnabled
                + ", oidcEnabled="
                + oidcEnabled
                + ", setupComplete="
                + isSetupComplete()
                + "]";
    }
}
