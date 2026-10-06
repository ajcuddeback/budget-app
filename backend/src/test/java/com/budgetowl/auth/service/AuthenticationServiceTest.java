package com.budgetowl.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.budgetowl.auth.domain.CredentialTransport;
import com.budgetowl.auth.domain.PasswordCredential;
import com.budgetowl.auth.domain.UserAccount;
import com.budgetowl.auth.persistence.PasswordCredentialRepository;
import com.budgetowl.auth.persistence.UserAccountRepository;
import com.budgetowl.common.AuthenticationFailedException;
import com.budgetowl.config.SecurityProperties;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The one behaviour that cannot be seen from outside: <b>a password comparison happens on every
 * path</b>, including the ones where there was nothing to compare against.
 *
 * <p>An integration test can show that the two responses are identical and that the two take
 * roughly the same time. Only this can show <em>why</em> — that the dummy comparison is actually
 * being run — which is what stops someone deleting it as dead code.
 *
 * <p>The encoder here counts its calls. The repositories are stubbed because the point is what the
 * service does when they find nothing, which is awkward to arrange with the real ones.
 */
class AuthenticationServiceTest {

    private final UserAccountRepository users = Mockito.mock(UserAccountRepository.class);
    private final PasswordCredentialRepository credentials =
            Mockito.mock(PasswordCredentialRepository.class);
    private final com.budgetowl.instance.persistence.InstanceSettingsRepository settings =
            Mockito.mock(com.budgetowl.instance.persistence.InstanceSettingsRepository.class);

    private final AtomicInteger comparisons = new AtomicInteger();
    private final PasswordEncoder countingEncoder =
            new PasswordEncoder() {
                @Override
                public String encode(CharSequence rawPassword) {
                    return "{counted}" + rawPassword;
                }

                @Override
                public boolean matches(CharSequence rawPassword, String encodedPassword) {
                    comparisons.incrementAndGet();
                    return encodedPassword.equals("{counted}" + rawPassword);
                }
            };

    private AuthenticationService service;

    @BeforeEach
    void buildService() {
        when(settings.findCurrent()).thenReturn(Optional.empty());
        service =
                new AuthenticationService(
                        users,
                        credentials,
                        settings,
                        countingEncoder,
                        new AuthRateLimiter(properties(), Clock.systemUTC()));
        comparisons.set(0);
    }

    @Test
    void comparesAPasswordEvenWhenNoSuchUserExists() {
        when(credentials.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authenticate("nobody@example.com", "a-example-passphrase"))
                .isInstanceOf(AuthenticationFailedException.class);

        assertThat(comparisons.get())
                .as(
                        "without this, 'no such user' returns in a millisecond and 'wrong password'"
                                + " does not — a difference measurable across the internet")
                .isEqualTo(1);
    }

    @Test
    void comparesAPasswordEvenWhenTheUserHasNoneSet() {
        when(credentials.findByEmail(anyString()))
                .thenReturn(Optional.of(credentialWithHash(null)));

        assertThatThrownBy(() -> authenticate("oidc@example.com", "a-example-passphrase"))
                .isInstanceOf(AuthenticationFailedException.class);

        assertThat(comparisons.get())
                .as("an OIDC-provisioned user with no password must not be distinguishable")
                .isEqualTo(1);
    }

    @Test
    void comparesExactlyOncePerAttemptForAWrongPassword() {
        when(credentials.findByEmail(anyString()))
                .thenReturn(Optional.of(credentialWithHash("{counted}the-right-example-one")));

        assertThatThrownBy(() -> authenticate("ada@example.com", "the-wrong-example-one"))
                .isInstanceOf(AuthenticationFailedException.class);

        assertThat(comparisons.get()).isEqualTo(1);
    }

    @Test
    void refusesADisabledUserWithTheSameFailureAsAWrongPassword() {
        when(credentials.findByEmail(anyString()))
                .thenReturn(Optional.of(credentialWithHash("{counted}the-right-example-one")));
        UserAccount disabled = UserAccount.member("ada@example.com", "Ada");
        disabled.disable();
        when(users.findByEmail(anyString())).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> authenticate("ada@example.com", "the-right-example-one"))
                .as("'this account is disabled' confirms that the address exists")
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void refusesAnOverlongPasswordWithoutAskingTheEncoderToHashIt() {
        when(credentials.findByEmail(anyString()))
                .thenReturn(Optional.of(credentialWithHash("{counted}the-right-example-one")));

        assertThatThrownBy(() -> authenticate("ada@example.com", "x".repeat(200)))
                .isInstanceOf(AuthenticationFailedException.class);

        assertThat(comparisons.get())
                .as("still one comparison, so the timing does not give the refusal away")
                .isEqualTo(1);
    }

    private void authenticate(String email, String password) {
        service.authenticate(email, password, "198.51.100.7", CredentialTransport.SESSION);
    }

    private static PasswordCredential credentialWithHash(String hash) {
        try {
            var constructor = PasswordCredential.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            PasswordCredential credential = constructor.newInstance();
            Field field = PasswordCredential.class.getDeclaredField("passwordHash");
            field.setAccessible(true);
            field.set(credential, hash);
            return credential;
        } catch (ReflectiveOperationException cannotBuild) {
            throw new IllegalStateException(cannotBuild);
        }
    }

    private static SecurityProperties properties() {
        return new SecurityProperties(
                4,
                new SecurityProperties.Session(Duration.ofHours(12)),
                new SecurityProperties.Token(Duration.ofDays(30), Duration.ofDays(180)),
                new SecurityProperties.RateLimit(
                        100, Duration.ofSeconds(1), Duration.ofMinutes(1)));
    }
}
