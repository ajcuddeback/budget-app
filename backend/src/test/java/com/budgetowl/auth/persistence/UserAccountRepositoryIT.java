package com.budgetowl.auth.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.auth.domain.AuthToken;
import com.budgetowl.auth.domain.PasswordCredential;
import com.budgetowl.auth.domain.UserAccount;
import com.budgetowl.auth.domain.UserStatus;
import com.budgetowl.persistence.PersistenceTestBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * User, credential and token reads.
 *
 * <p>The email assertions look pedantic and are not. {@code users.email} is {@code citext}, but a
 * Java {@code String} binds as a JDBC {@code varchar}, and PostgreSQL resolves {@code citext =
 * varchar} by casting both sides to {@code text} — case-sensitively. A derived {@code findByEmail}
 * therefore passes any test that types the address the same way twice, and fails in the hands of
 * the one user who capitalises their own name.
 */
@SpringBootTest
class UserAccountRepositoryIT extends PersistenceTestBase {

    private static final String ENCODED = "{bcrypt}$2a$12$0123456789012345678901";
    private static final String TOKEN_DIGEST =
            "0000000000000000000000000000000000000000000000000000000000000001";

    @Autowired private UserAccountRepository users;
    @Autowired private PasswordCredentialRepository credentials;
    @Autowired private AuthTokenRepository tokens;

    @Test
    void findsAUserByAnAddressTypedWithDifferentCapitalisation() {
        save(UserAccount.member("ada@example.com", "Ada"));

        assertThat(users.findByEmail("Ada@Example.COM"))
                .hasValueSatisfying(found -> assertThat(found.displayName()).isEqualTo("Ada"));
        assertThat(users.existsByEmail("ADA@EXAMPLE.COM")).isTrue();
    }

    @Test
    void reportsNothingForAnAddressThatHasNeverRegistered() {
        assertThat(users.findByEmail("nobody@example.com")).isEmpty();
        assertThat(users.existsByEmail("nobody@example.com")).isFalse();
    }

    @Test
    void aFreshInstanceHasNoUsers() {
        assertThat(users.count()).isZero();
    }

    @Test
    void anewUserIsActiveAndNotAnAdministrator() {
        UUID id = save(UserAccount.member("ada@example.com", "Ada")).id();

        assertThat(users.findSummaryById(id))
                .hasValueSatisfying(
                        summary -> {
                            assertThat(summary.status()).isEqualTo(UserStatus.ACTIVE);
                            assertThat(summary.instanceAdmin()).isFalse();
                            assertThat(summary.createdAt()).isNotNull();
                        });
    }

    @Test
    void aNewUserHasNoPasswordUntilOneIsSet() {
        UUID id = save(UserAccount.member("oidc@example.com", "Provisioned")).id();

        assertThat(credentials.findById(id))
                .hasValueSatisfying(it -> assertThat(it.isSet()).isFalse());
    }

    @Test
    void verifiesAPasswordWithoutEverHandingTheHashBack() {
        UUID id = save(UserAccount.member("ada@example.com", "Ada")).id();
        transaction.executeWithoutResult(status -> credentials.updatePassword(id, ENCODED));

        PasswordCredential credential = credentials.findByEmail("ADA@Example.com").orElseThrow();

        assertThat(credential.isSet()).isTrue();
        assertThat(credential.matches("hunter2", (raw, encoded) -> encoded.equals(ENCODED)))
                .isTrue();
        assertThat(credential.matches("hunter2", (raw, encoded) -> false)).isFalse();
    }

    @Test
    void reportsNoMatchForAUserWithNoPasswordRatherThanThrowing() {
        UUID id = save(UserAccount.member("oidc@example.com", "Provisioned")).id();

        PasswordCredential credential = credentials.findById(id).orElseThrow();

        assertThat(credential.matches("anything", (raw, encoded) -> true)).isFalse();
    }

    @Test
    void findsATokenByItsHashAndBringsTheUserWithIt() {
        UserAccount ada = save(UserAccount.member("ada@example.com", "Ada"));
        transaction.executeWithoutResult(
                status ->
                        tokens.save(
                                AuthToken.issue(
                                        ada,
                                        TOKEN_DIGEST,
                                        "Ada's phone",
                                        Instant.now().plus(30, ChronoUnit.DAYS))));

        assertThat(tokens.findByTokenHash(TOKEN_DIGEST))
                .hasValueSatisfying(
                        token -> {
                            assertThat(token.deviceLabel()).isEqualTo("Ada's phone");
                            assertThat(token.user().email()).isEqualTo("ada@example.com");
                            assertThat(token.isLiveAt(Instant.now())).isTrue();
                        });
    }

    @Test
    void revokesEveryLiveTokenOfOneUserInOneStatement() {
        // "A removed member keeps using a mobile token" is only mitigated if this is immediate.
        UserAccount ada = save(UserAccount.member("ada@example.com", "Ada"));
        transaction.executeWithoutResult(
                status -> {
                    tokens.save(
                            AuthToken.issue(
                                    ada,
                                    TOKEN_DIGEST,
                                    "phone",
                                    Instant.now().plus(30, ChronoUnit.DAYS)));
                    tokens.save(
                            AuthToken.issue(
                                    ada,
                                    TOKEN_DIGEST.replace("1", "2"),
                                    "tablet",
                                    Instant.now().plus(30, ChronoUnit.DAYS)));
                });

        Integer revoked =
                transaction.execute(status -> tokens.revokeAllForUser(ada.id(), Instant.now()));

        assertThat(revoked).isEqualTo(2);
        assertThat(tokens.countByUserIdAndRevokedAtIsNull(ada.id())).isZero();
        assertThat(tokens.findSummariesByUserId(ada.id()))
                .hasSize(2)
                .allSatisfy(summary -> assertThat(summary.revokedAt()).isNotNull());
    }

    @Test
    void listsOnlyTheCallersOwnDevices() {
        UserAccount ada = save(UserAccount.member("ada@example.com", "Ada"));
        UserAccount grace = save(UserAccount.member("grace@example.com", "Grace"));
        transaction.executeWithoutResult(
                status ->
                        tokens.save(
                                AuthToken.issue(
                                        ada,
                                        TOKEN_DIGEST,
                                        "phone",
                                        Instant.now().plus(30, ChronoUnit.DAYS))));

        assertThat(tokens.findSummariesByUserId(grace.id())).isEmpty();
        assertThat(tokens.findSummariesByUserId(ada.id())).hasSize(1);
    }

    private UserAccount save(UserAccount user) {
        return transaction.execute(status -> users.save(user));
    }
}
