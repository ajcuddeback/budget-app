package com.budgetowl.instance.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.instance.domain.InstanceSettings;
import com.budgetowl.persistence.PersistenceTestBase;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * <b>The instance always has at least one way to log in.</b>
 *
 * <p>Password login is a permanent capability (ADR-0018) and may only be <em>hidden</em> on an
 * instance where OIDC is proven working. "Proven working" was recorded as a timestamp and checked
 * as a timestamp, which left this sequence legal and every step of it defensible on its own:
 *
 * <ol>
 *   <li>switch OIDC on and configure it;
 *   <li>an {@code OWNER} signs in through it — the proof;
 *   <li>hide password login — correct, and exactly what the setting is for;
 *   <li>switch OIDC off.
 * </ol>
 *
 * <p>At the end of it {@code password_login_enabled} and {@code oidc_enabled} are both false. On a
 * self-hosted box that is the operator locked out of their own financial records, with psql on the
 * host as the only way back in — the failure ADR-0016 exists to prevent, arrived at without anybody
 * doing anything obviously wrong.
 *
 * <p>Two things stop it now: {@code ck_instance_settings_password_login_lockout} refuses the state
 * (see {@code SchemaConstraintsIT}), and {@link InstanceSettings#disableOidc()} restores password
 * login so that an administrator turning OIDC off never meets the error at all. This test drives
 * the second one through real JPA to a real commit, because the entity writes the whole row and it
 * is the commit that the constraint sees.
 */
@SpringBootTest
class LoginRouteIT extends PersistenceTestBase {

    @Autowired private InstanceSettingsRepository settings;

    @Test
    void switchingOidcOffBringsPasswordLoginBackWithIt() {
        hidePasswordLoginBehindAProvenOidc();

        transaction.executeWithoutResult(
                status -> {
                    InstanceSettings current = settings.findCurrent().orElseThrow();
                    current.disableOidc();
                    settings.save(current);
                });

        assertThat(settings.findCurrent())
                .hasValueSatisfying(
                        current -> {
                            assertThat(current.isOidcEnabled()).isFalse();
                            assertThat(current.isPasswordLoginEnabled()).isTrue();
                            assertThat(current.oidcOwnerLoginAt()).isNull();
                        });
        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT password_login_enabled OR oidc_enabled
                                  FROM instance_settings WHERE id = 1
                                """,
                                Boolean.class))
                .isTrue();
    }

    @Test
    void pointingOidcAtADifferentProviderBringsPasswordLoginBackToo() {
        // The proof is a login against a specific provider. Re-pointing the instance at another
        // one and keeping password login hidden would mean the new provider's owner of the
        // client id decides who gets into this household's finances, with nobody having tested it.
        hidePasswordLoginBehindAProvenOidc();

        transaction.executeWithoutResult(
                status -> {
                    InstanceSettings current = settings.findCurrent().orElseThrow();
                    current.configureOidc(
                            "https://other-idp.example.net", "budget-owl", "not-a-real-secret");
                    settings.save(current);
                });

        assertThat(settings.findCurrent())
                .hasValueSatisfying(
                        current -> {
                            assertThat(current.isOidcEnabled()).isTrue();
                            assertThat(current.oidcOwnerLoginAt()).isNull();
                            assertThat(current.isPasswordLoginEnabled()).isTrue();
                        });
    }

    private void hidePasswordLoginBehindAProvenOidc() {
        transaction.executeWithoutResult(
                status -> {
                    InstanceSettings current = settings.findCurrent().orElseThrow();
                    current.configureOidc(
                            "https://idp.example.com", "budget-owl", "not-a-real-secret");
                    current.recordOwnerOidcLoginAt(Instant.now());
                    current.disablePasswordLogin();
                    settings.save(current);
                });

        assertThat(settings.findCurrent())
                .hasValueSatisfying(
                        current -> assertThat(current.isPasswordLoginEnabled()).isFalse());
    }
}
