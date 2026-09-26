package com.budgetowl.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.budgetowl.domain.Ids;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class AuthTokenTest {

    private static final String DIGEST =
            "0000000000000000000000000000000000000000000000000000000000000001";
    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

    private final UserAccount ada = UserAccount.member("ada@example.com", "Ada");

    @Test
    void aFreshTokenIsLiveAndUnused() {
        AuthToken token = issued(NOW.plus(30, ChronoUnit.DAYS));

        assertThat(token.isLiveAt(NOW)).isTrue();
        assertThat(token.lastUsedAt()).isNull();
        assertThat(token.revokedAt()).isNull();
        assertThat(token.deviceLabel()).isEqualTo("Ada's phone");
        assertThat(token.user()).isSameAs(ada);
        assertThat(token.expiresAt()).isEqualTo(NOW.plus(30, ChronoUnit.DAYS));
        assertThat(token.createdAt()).isNull();
    }

    @Test
    void anExpiredTokenIsNotLive() {
        assertThat(issued(NOW.minusSeconds(1)).isLiveAt(NOW)).isFalse();
        assertThat(issued(NOW).isLiveAt(NOW)).as("expiry is not inclusive").isFalse();
    }

    @Test
    void aRevokedTokenIsNotLiveEvenBeforeItExpires() {
        // "An attacker who has stolen a cookie keeps using it after the member logs out" is only
        // mitigated if revocation beats expiry.
        AuthToken token = issued(NOW.plus(30, ChronoUnit.DAYS));

        token.revokeAt(NOW);

        assertThat(token.isLiveAt(NOW)).isFalse();
        assertThat(token.revokedAt()).isEqualTo(NOW);
    }

    @Test
    void revokingTwiceKeepsTheFirstInstant() {
        // Logout is idempotent, and the audit trail should say when access actually ended.
        AuthToken token = issued(NOW.plus(30, ChronoUnit.DAYS));

        token.revokeAt(NOW);
        token.revokeAt(NOW.plus(1, ChronoUnit.HOURS));

        assertThat(token.revokedAt()).isEqualTo(NOW);
    }

    @Test
    void recordsWhenItWasLastUsedAndWhenItWasExtended() {
        AuthToken token = issued(NOW.plus(30, ChronoUnit.DAYS));

        token.markUsedAt(NOW.plus(1, ChronoUnit.HOURS));
        token.extendTo(NOW.plus(60, ChronoUnit.DAYS));

        assertThat(token.lastUsedAt()).isEqualTo(NOW.plus(1, ChronoUnit.HOURS));
        assertThat(token.expiresAt()).isEqualTo(NOW.plus(60, ChronoUnit.DAYS));
        assertThatNullPointerException().isThrownBy(() -> token.markUsedAt(null));
        assertThatNullPointerException().isThrownBy(() -> token.extendTo(null));
        assertThatNullPointerException().isThrownBy(() -> token.revokeAt(null));
    }

    @Test
    void refusesToExistWithoutAUserAHashALabelOrAnExpiry() {
        Instant expiry = NOW.plus(30, ChronoUnit.DAYS);

        assertThatNullPointerException()
                .isThrownBy(() -> AuthToken.issue(null, DIGEST, "phone", expiry));
        assertThatNullPointerException()
                .isThrownBy(() -> AuthToken.issue(ada, null, "phone", expiry));
        assertThatNullPointerException()
                .isThrownBy(() -> AuthToken.issue(ada, DIGEST, null, expiry));
        assertThatNullPointerException()
                .isThrownBy(() -> AuthToken.issue(ada, DIGEST, "phone", null));
    }

    @Test
    void identityComesFromTheRow() {
        AuthToken saved = Ids.withId(issued(NOW.plus(1, ChronoUnit.DAYS)));
        AuthToken sameRow = Ids.withId(issued(NOW.plus(1, ChronoUnit.DAYS)), Ids.idOf(saved));

        assertThat(saved).isEqualTo(sameRow).isNotEqualTo(Ids.withId(issued(NOW.plusSeconds(60))));
        assertThat(saved.hashCode()).isEqualTo(sameRow.hashCode());
        assertThat(saved).isNotEqualTo("not a token");
        AuthToken unsaved = issued(NOW.plusSeconds(60));
        assertThat(unsaved).isEqualTo(unsaved).isNotEqualTo(issued(NOW.plusSeconds(60)));
    }

    @Test
    void neverNamesTheDeviceOrTheHashInItsStringForm() {
        assertThat(Ids.withId(issued(NOW.plusSeconds(60))).toString())
                .doesNotContain("Ada's phone")
                .doesNotContain(DIGEST);
    }

    private AuthToken issued(Instant expiresAt) {
        return AuthToken.issue(ada, DIGEST, "Ada's phone", expiresAt);
    }
}
