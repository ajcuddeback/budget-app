package com.budgetowl.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.budgetowl.common.RateLimitedException;
import com.budgetowl.config.SecurityProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Backoff behaviour, with time under the test's control rather than the wall clock's.
 *
 * <p>The property that matters most is the one about what <em>cannot</em> happen: the lock always
 * lifts. A permanent lock would let anyone who knows a member's email address keep them out of
 * their own financial records, on their own hardware, with nobody to call.
 */
class AuthRateLimiterTest {

    private static final Duration INITIAL = Duration.ofSeconds(2);
    private static final Duration MAXIMUM = Duration.ofMinutes(15);

    private Instant now = Instant.parse("2026-09-26T09:00:00Z");
    private final Clock clock =
            new Clock() {
                @Override
                public ZoneOffset getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(java.time.ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return now;
                }
            };

    private final AuthRateLimiter limiter =
            new AuthRateLimiter(
                    new SecurityProperties(
                            4,
                            new SecurityProperties.Session(Duration.ofHours(12)),
                            new SecurityProperties.Token(Duration.ofDays(30), Duration.ofDays(180)),
                            new SecurityProperties.RateLimit(3, INITIAL, MAXIMUM)),
                    clock);

    private final List<String> keys =
            List.of(AuthRateLimiter.ip("198.51.100.7"), AuthRateLimiter.email("ada@example.com"));

    @Test
    void allowsTheFirstAttemptsWithoutDelay() {
        for (int attempt = 0; attempt < 3; attempt++) {
            limiter.recordFailure(keys);
            assertThatCode(() -> limiter.requireAllowed(keys)).doesNotThrowAnyException();
        }
    }

    @Test
    void backsOffOnceThePermittedFailuresAreUsedUp() {
        failTimes(4);

        assertThatThrownBy(() -> limiter.requireAllowed(keys))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void doublesTheDelayWithEachFurtherFailure() {
        failTimes(4);
        Duration first = waitRequired();

        now = now.plus(first).plusSeconds(1);
        limiter.recordFailure(keys);
        Duration second = waitRequired();

        assertThat(second).isEqualTo(first.multipliedBy(2));
    }

    @Test
    void neverLocksForLongerThanTheMaximum() {
        failTimes(40);

        assertThat(waitRequired())
                .as("a lock that never lifts is a denial of service against the real user")
                .isLessThanOrEqualTo(MAXIMUM);
    }

    @Test
    void liftsTheLockOnceTheDelayHasPassed() {
        failTimes(4);
        Duration wait = waitRequired();

        now = now.plus(wait).plusSeconds(1);

        assertThatCode(() -> limiter.requireAllowed(keys)).doesNotThrowAnyException();
    }

    @Test
    void forgetsThePastOnceAKeyHasBeenQuietLongEnough() {
        failTimes(6);

        now = now.plus(MAXIMUM.multipliedBy(3));
        limiter.recordFailure(keys);

        assertThatCode(() -> limiter.requireAllowed(keys))
                .as("a member who mistypes once a month must not inherit their own history")
                .doesNotThrowAnyException();
    }

    @Test
    void clearsTheBackoffOnASuccessfulAttempt() {
        failTimes(4);

        limiter.recordSuccess(keys);

        assertThatCode(() -> limiter.requireAllowed(keys)).doesNotThrowAnyException();
    }

    @Test
    void throttlesAnEmailEvenFromADifferentAddress() {
        failTimes(4);

        List<String> fromElsewhere =
                List.of(
                        AuthRateLimiter.ip("203.0.113.9"),
                        AuthRateLimiter.email("ada@example.com"));

        assertThatThrownBy(() -> limiter.requireAllowed(fromElsewhere))
                .as("a botnet is many addresses guessing at one account")
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void throttlesAnAddressEvenAcrossDifferentEmails() {
        failTimes(4);

        List<String> sprayingElsewhere =
                List.of(
                        AuthRateLimiter.ip("198.51.100.7"),
                        AuthRateLimiter.email("grace@example.com"));

        assertThatThrownBy(() -> limiter.requireAllowed(sprayingElsewhere))
                .as("password spraying is one address guessing at many accounts")
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void treatsAnEmailTheSameHoweverItIsCapitalised() {
        assertThat(AuthRateLimiter.email("Ada@Example.com"))
                .isEqualTo(AuthRateLimiter.email("ada@example.com"));
    }

    private void failTimes(int times) {
        for (int attempt = 0; attempt < times; attempt++) {
            limiter.recordFailure(keys);
        }
    }

    private Duration waitRequired() {
        try {
            limiter.requireAllowed(keys);
        } catch (RateLimitedException limited) {
            return limited.retryAfter();
        }
        throw new AssertionError("expected the limiter to be backing off");
    }
}
