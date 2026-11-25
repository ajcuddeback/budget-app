package com.budgetowl.imports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.budgetowl.common.RateLimitedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** One parse per user, two per instance, thirty per user per ten minutes (rule 3). */
class ImportThrottleTest {

    private static final UUID ADA = UUID.randomUUID();
    private static final UUID GRACE = UUID.randomUUID();

    @Test
    void aSecondParseForTheSameUserIsRefusedUntilTheFirstEnds() {
        ImportGate gate = new ImportGate();

        try (ImportGate.Permit first = gate.enter(ADA)) {
            assertThatThrownBy(() -> gate.enter(ADA))
                    .isInstanceOfSatisfying(
                            RateLimitedException.class,
                            refused -> assertThat(refused.retryAfter()).isPositive());
        }
        gate.enter(ADA).close();
    }

    @Test
    void theInstanceRunsTwoParsesAndRefusesAThird() {
        ImportGate gate = new ImportGate();

        try (ImportGate.Permit one = gate.enter(ADA);
                ImportGate.Permit two = gate.enter(GRACE)) {
            assertThatThrownBy(() -> gate.enter(UUID.randomUUID()))
                    .isInstanceOf(RateLimitedException.class);
        }
        gate.enter(UUID.randomUUID()).close();
    }

    @Test
    void aRefusedUserDoesNotHoldTheirSlot() {
        ImportGate gate = new ImportGate();
        ImportGate.Permit one = gate.enter(UUID.randomUUID());
        ImportGate.Permit two = gate.enter(UUID.randomUUID());

        assertThatThrownBy(() -> gate.enter(ADA)).isInstanceOf(RateLimitedException.class);
        one.close();

        gate.enter(ADA).close();
        two.close();
    }

    @Test
    void closingTwiceReleasesOnce() {
        ImportGate gate = new ImportGate();
        ImportGate.Permit permit = gate.enter(ADA);
        permit.close();
        permit.close();

        try (ImportGate.Permit one = gate.enter(GRACE);
                ImportGate.Permit two = gate.enter(ADA)) {
            assertThatThrownBy(() -> gate.enter(UUID.randomUUID()))
                    .isInstanceOf(RateLimitedException.class);
        }
    }

    @Test
    void theThirtyFirstCallInTheWindowIsRefusedAndTheNextWindowIsNot() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-25T12:00:00Z"));
        ImportRateLimiter limiter = new ImportRateLimiter(clock);
        for (int call = 0; call < ImportRateLimiter.PERMITTED_PER_WINDOW; call++) {
            limiter.requireAllowed(ADA);
        }

        assertThatThrownBy(() -> limiter.requireAllowed(ADA))
                .isInstanceOfSatisfying(
                        RateLimitedException.class,
                        refused ->
                                assertThat(refused.retryAfter())
                                        .isLessThanOrEqualTo(Duration.ofMinutes(10)));
        limiter.requireAllowed(GRACE);

        clock.now = clock.now.plus(ImportRateLimiter.WINDOW);
        limiter.requireAllowed(ADA);
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
