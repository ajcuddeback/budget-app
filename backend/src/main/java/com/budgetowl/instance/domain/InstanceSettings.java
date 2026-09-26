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
 *   <li>{@code ck_instance_settings_password_login_lockout} refuses any state with no login route
 *       at all: password login may be off only while OIDC is <em>switched on</em> and an {@code
 *       OWNER} has actually completed a login through it. Checking the timestamp alone (V6) let
 *       OIDC be switched off afterwards, which is a lockout; V8 ties the two together. A mechanism
 *       rather than a warning, so nobody can lock themselves out of their own server.
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
     * Refused by {@code ck_instance_settings_password_login_lockout} unless OIDC is switched on
     * <em>and</em> an {@code OWNER} has signed in through it. The database says no even if a
     * service forgets to, and it says no at {@code COMMIT}, so a transaction that turns OIDC off
     * later in its own life is refused as well.
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

    /**
     * Points the instance at an OIDC provider and switches it on.
     *
     * <p>Changing the issuer or the client discards {@link #oidcOwnerLoginAt}. That timestamp is
     * the evidence that <em>this</em> provider can sign an {@code OWNER} in; carrying it across to
     * a different one would let password login stay hidden on the strength of a login that happened
     * somewhere else.
     */
    public void configureOidc(String issuerUri, String clientId, String clientSecret) {
        String newIssuer = Objects.requireNonNull(issuerUri, "issuerUri");
        String newClientId = Objects.requireNonNull(clientId, "clientId");
        if (!newIssuer.equals(oidcIssuerUri) || !newClientId.equals(oidcClientId)) {
            this.oidcOwnerLoginAt = null;
            this.passwordLoginEnabled = true;
        }
        this.oidcIssuerUri = newIssuer;
        this.oidcClientId = newClientId;
        this.oidcClientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
        this.oidcEnabled = true;
    }

    /**
     * Switches OIDC off, and brings password login back with it.
     *
     * <p>Turning off the only login route locks the operator out of their own financial records on
     * their own hardware, with psql on the host as the only way back in. So this is not merely "set
     * two booleans": the OIDC login route is disappearing, and password login — the permanent
     * capability (ADR-0018) — is restored in the same change so that one route always remains.
     * {@code ck_instance_settings_password_login_lockout} refuses the transaction otherwise, which
     * is the backstop; doing it here is what stops an administrator meeting that error at all.
     *
     * <p>{@link #oidcOwnerLoginAt} is cleared too. It attests that an {@code OWNER} completed an
     * OIDC login against a provider that is switched on, and once it is not, it attests to nothing.
     * Re-enabling OIDC therefore needs a fresh owner login before password login can be hidden
     * again.
     */
    public void disableOidc() {
        this.oidcEnabled = false;
        this.oidcProvisioningEnabled = false;
        this.oidcOwnerLoginAt = null;
        this.passwordLoginEnabled = true;
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
