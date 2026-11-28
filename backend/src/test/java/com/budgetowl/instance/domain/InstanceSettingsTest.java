package com.budgetowl.instance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Instance settings, as the application sees them.
 *
 * <p>The rules that must survive a bug in this class — password login cannot be disabled before an
 * {@code OWNER} has completed an OIDC login, OIDC cannot be switched on unconfigured, setup is
 * claimed once — are checked by the database as well, in {@code SchemaConstraintsIT} and {@code
 * FirstUserSetupIT}.
 */
class InstanceSettingsTest {

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

    @Test
    void aFreshInstanceIsClosedToRegistrationAndOpenToPasswordLogin() {
        InstanceSettings settings = fresh();

        assertThat(settings.isRegistrationOpen()).isFalse();
        assertThat(settings.isPasswordLoginEnabled()).isTrue();
        assertThat(settings.isOidcEnabled()).isFalse();
        assertThat(settings.isOidcProvisioningEnabled()).isFalse();
        assertThat(settings.isSetupComplete()).isFalse();
        assertThat(settings.setupCompletedAt()).isNull();
        assertThat(settings.oidcOwnerLoginAt()).isNull();
        assertThat(settings.oidcIssuerUri()).isNull();
        assertThat(settings.oidcClientId()).isNull();
        assertThat(settings.id()).isEqualTo((short) 0);
    }

    @Test
    void registrationCanBeOpenedAndClosedAgain() {
        // "Registration re-opened, then closed, with an invitation outstanding" is a listed edge
        // case, so both directions have to work.
        InstanceSettings settings = fresh();

        settings.openRegistration();
        assertThat(settings.isRegistrationOpen()).isTrue();

        settings.closeRegistration();
        assertThat(settings.isRegistrationOpen()).isFalse();
    }

    @Test
    void passwordLoginCanBeTurnedOffAndBackOn() {
        // Always present in the build (ADR-0018); this only hides it on one instance. Whether it
        // is *allowed* yet is the database's call, not this class's.
        InstanceSettings settings = fresh();

        settings.disablePasswordLogin();
        assertThat(settings.isPasswordLoginEnabled()).isFalse();

        settings.enablePasswordLogin();
        assertThat(settings.isPasswordLoginEnabled()).isTrue();
    }

    @Test
    void configuringOidcTurnsItOnAndKeepsTheSecretOutOfTheOrdinaryAccessors() {
        InstanceSettings settings = fresh();

        settings.configureOidc("https://idp.example.com", "budget-owl", "not-a-real-secret");

        assertThat(settings.isOidcEnabled()).isTrue();
        assertThat(settings.oidcIssuerUri()).isEqualTo("https://idp.example.com");
        assertThat(settings.oidcClientId()).isEqualTo("budget-owl");
        assertThat(settings.oidcClientSecretForClientConfiguration())
                .isEqualTo("not-a-real-secret");
        assertThat(settings.toString()).doesNotContain("not-a-real-secret");
    }

    @Test
    void refusesAnIncompleteOidcConfiguration() {
        InstanceSettings settings = fresh();

        assertThatNullPointerException()
                .isThrownBy(() -> settings.configureOidc(null, "budget-owl", "s"));
        assertThatNullPointerException()
                .isThrownBy(() -> settings.configureOidc("https://idp.example.com", null, "s"));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                settings.configureOidc(
                                        "https://idp.example.com", "budget-owl", null));
    }

    @Test
    void turningOidcOffAlsoStopsItProvisioningUsers() {
        // Otherwise the setting reads as "on" while the provider is gone, which is the kind of
        // stale switch somebody re-enables OIDC next to and gets a surprise from.
        InstanceSettings settings = fresh();
        settings.configureOidc("https://idp.example.com", "budget-owl", "not-a-real-secret");
        settings.allowOidcProvisioning(true);

        settings.disableOidc();

        assertThat(settings.isOidcEnabled()).isFalse();
        assertThat(settings.isOidcProvisioningEnabled()).isFalse();
    }

    @Test
    void turningOidcOffBringsPasswordLoginBackRatherThanLeavingNoLoginRouteAtAll() {
        // The finding this test exists for: enable OIDC, prove it with an OWNER login, hide
        // password login (legitimate), then turn OIDC off — and the instance had no way in at
        // all, on somebody's own hardware, holding their own financial records.
        InstanceSettings settings = fresh();
        settings.configureOidc("https://idp.example.com", "budget-owl", "not-a-real-secret");
        settings.recordOwnerOidcLoginAt(NOW);
        settings.disablePasswordLogin();

        settings.disableOidc();

        assertThat(settings.isOidcEnabled()).isFalse();
        assertThat(settings.isPasswordLoginEnabled()).isTrue();
    }

    @Test
    void turningOidcOffDiscardsTheOwnerLoginItAttestedTo() {
        // The timestamp means "an OWNER signed in through the provider that is switched on". With
        // the provider switched off it means nothing, and leaving it behind would let password
        // login be hidden again the moment OIDC came back — on the strength of a login against
        // whatever was configured before.
        InstanceSettings settings = fresh();
        settings.configureOidc("https://idp.example.com", "budget-owl", "not-a-real-secret");
        settings.recordOwnerOidcLoginAt(NOW);

        settings.disableOidc();

        assertThat(settings.oidcOwnerLoginAt()).isNull();
    }

    @Test
    void pointingOidcAtADifferentProviderDiscardsTheOldProof() {
        InstanceSettings settings = fresh();
        settings.configureOidc("https://idp.example.com", "budget-owl", "not-a-real-secret");
        settings.recordOwnerOidcLoginAt(NOW);
        settings.disablePasswordLogin();

        settings.configureOidc("https://attacker.example.net", "budget-owl", "not-a-real-secret");

        assertThat(settings.oidcOwnerLoginAt()).isNull();
        assertThat(settings.isPasswordLoginEnabled()).isTrue();
    }

    @Test
    void rotatingTheClientSecretDiscardsTheProofToo() {
        // THIS TEST ASSERTED THE OPPOSITE UNTIL ADR-0034, with the reasoning that "rotating the
        // client secret is not a change of provider, and forcing password login back on for it
        // would train an administrator to ignore the setting". That reasoning is about annoyance.
        // The thing on the other side of it is a lockout: the secret is half the client
        // credential, so a mistyped rotation leaves a route nobody has proven, with password login
        // still hidden and the CHECK constraint unable to see the difference — it can only tell
        // that OIDC is on and that somebody once signed in. One routine PATCH, nobody can sign in,
        // host shell the only way back. Annoyance loses to that.
        InstanceSettings settings = fresh();
        settings.configureOidc("https://idp.example.com", "budget-owl", "not-a-real-secret");
        settings.recordOwnerOidcLoginAt(NOW);
        settings.disablePasswordLogin();

        settings.configureOidc("https://idp.example.com", "budget-owl", "rotated-not-a-secret");

        assertThat(settings.oidcOwnerLoginAt()).isNull();
        assertThat(settings.isPasswordLoginEnabled()).isTrue();
    }

    @Test
    void resavingTheIdenticalConfigurationDisturbsNothing() {
        // The other half of ADR-0034, and the reason it is a comparison rather than "any write to
        // the secret clears the proof": an administrator who saves the form without changing
        // anything — or who changes only the provisioning flag — sends all three values back
        // unchanged, and must not be punished for it. Only a DIFFERENT credential is unproven.
        InstanceSettings settings = fresh();
        settings.configureOidc("https://idp.example.com", "budget-owl", "not-a-real-secret");
        settings.recordOwnerOidcLoginAt(NOW);
        settings.disablePasswordLogin();

        settings.configureOidc("https://idp.example.com", "budget-owl", "not-a-real-secret");

        assertThat(settings.oidcOwnerLoginAt()).isEqualTo(NOW);
        assertThat(settings.isPasswordLoginEnabled()).isFalse();
    }

    @Test
    void recordsWhenAnOwnerFirstSignedInThroughOidc() {
        // The evidence that disabling password login will not lock everyone out.
        InstanceSettings settings = fresh();

        settings.recordOwnerOidcLoginAt(NOW);

        assertThat(settings.oidcOwnerLoginAt()).isEqualTo(NOW);
        assertThatNullPointerException().isThrownBy(() -> settings.recordOwnerOidcLoginAt(null));
    }

    @Test
    void describesItselfWithoutNamingAnySecret() {
        assertThat(fresh().toString())
                .contains("registrationOpen=false")
                .contains("passwordLoginEnabled=true")
                .contains("setupComplete=false");
    }

    private InstanceSettings fresh() {
        // The row is created by migration V6 with its defaults; a bare instance has Java's.
        InstanceSettings settings = new InstanceSettings();
        settings.enablePasswordLogin();
        return settings;
    }
}
