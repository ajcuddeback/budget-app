package com.budgetowl.auth.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.auth.domain.AuthToken;
import com.budgetowl.auth.domain.PasswordCredential;
import com.budgetowl.persistence.PersistenceTestBase;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * <b>A bulk {@code @Modifying} update goes round the persistence context, and an entity loaded
 * before it does not hear about it.</b>
 *
 * <p>Hibernate writes every column when it flushes a dirty entity, so a stale instance does not
 * merely hold an old value — it <em>restores</em> it. The two statements in this file are the ones
 * where that is a security failure rather than a lost field:
 *
 * <ul>
 *   <li>{@code revokeAllForUser} is what "removing a member immediately invalidates their sessions
 *       and tokens" is built on. A stale {@link AuthToken} flushing {@code revoked_at = NULL} back
 *       hands a removed member their access again.
 *   <li>{@code updatePassword} is a password change. A stale {@link PasswordCredential} means the
 *       old password still verifies — which is the entire thing a user changing a leaked password
 *       is trying to stop.
 * </ul>
 *
 * <p>The fix is {@code clearAutomatically = true, flushAutomatically = true} on the queries. These
 * tests drive the sequences that would otherwise spring the trap.
 */
@SpringBootTest
class BulkUpdateVisibilityIT extends PersistenceTestBase {

    private static final String TOKEN_HASH =
            "0000000000000000000000000000000000000000000000000000000000000001";
    private static final String OLD_SECRET = "ada-example-passphrase";
    private static final String NEW_SECRET = "ada-example-passphrase-two";

    @Autowired private AuthTokenRepository tokens;

    @Autowired private PasswordCredentialRepository credentials;

    @Autowired private PasswordEncoder passwordEncoder;

    private UUID user;

    @BeforeEach
    void seedAUserWithAPasswordAndALiveToken() {
        user = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, display_name, password_hash) VALUES (?, ?, ?, ?)",
                user,
                "ada@example.com",
                "Ada",
                passwordEncoder.encode(OLD_SECRET));
        jdbc.update(
                """
                INSERT INTO auth_tokens (id, user_id, token_hash, device_label, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                user,
                TOKEN_HASH,
                "Ada's phone",
                OffsetDateTime.now().plusDays(30));
    }

    @Test
    void aTokenTouchedOnTheWayInCannotUnRevokeItself() {
        // The shape of a real request: the bearer filter loads the token and marks it used, and
        // somewhere in the same transaction the user is removed from the household, which revokes
        // everything they hold. If the loaded token then flushes, it writes its own snapshot back
        // — revoked_at = NULL — and the removed member's phone keeps working.
        transaction.executeWithoutResult(
                status -> {
                    AuthToken inFlight = tokens.findByTokenHash(TOKEN_HASH).orElseThrow();

                    assertThat(tokens.revokeAllForUser(user, Instant.now())).isEqualTo(1);

                    // A "this token was just used" write, which every authenticated request does.
                    inFlight.markUsedAt(Instant.now());
                });

        assertThat(revokedAt()).as("revocation is immediate, and it stays").isNotNull();
        assertThat(tokens.countByUserIdAndRevokedAtIsNull(user)).isZero();
    }

    @Test
    void theOldPasswordStopsVerifyingInsideTheTransactionThatChangedIt() {
        // PasswordService loads the credential to check the current password and then calls
        // updatePassword. Without the context being cleared, a re-read in the same transaction
        // returns the very same instance, still holding the old hash: the password change reports
        // success while the password it was meant to retire still opens the account.
        transaction.executeWithoutResult(
                status -> {
                    PasswordCredential before = credentials.findById(user).orElseThrow();
                    assertThat(before.matches(OLD_SECRET, passwordEncoder::matches)).isTrue();

                    credentials.updatePassword(user, passwordEncoder.encode(NEW_SECRET));

                    PasswordCredential after = credentials.findById(user).orElseThrow();
                    assertThat(after.matches(NEW_SECRET, passwordEncoder::matches))
                            .as("the new password is what is stored")
                            .isTrue();
                    assertThat(after.matches(OLD_SECRET, passwordEncoder::matches))
                            .as("the old password is gone, not merely overwritten later")
                            .isFalse();
                });

        assertThat(credentials.findById(user))
                .hasValueSatisfying(
                        stored ->
                                assertThat(stored.matches(NEW_SECRET, passwordEncoder::matches))
                                        .isTrue());
    }

    private Instant revokedAt() {
        OffsetDateTime revoked =
                jdbc.queryForObject(
                        "SELECT revoked_at FROM auth_tokens WHERE user_id = ?",
                        OffsetDateTime.class,
                        user);
        return revoked == null ? null : revoked.toInstant();
    }
}
