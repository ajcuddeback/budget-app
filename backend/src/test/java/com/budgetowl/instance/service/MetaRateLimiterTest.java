package com.budgetowl.instance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.budgetowl.common.RateLimitedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The fixed window on {@code GET /api/meta}, with a clock the test moves: the window rolls, the
 * refusal is never permanent, one address does not throttle another, and the set of tracked
 * addresses stays bounded. {@code MetaIT} proves the {@code 429} over HTTP; this proves time.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class MetaRateLimiterTest {

    private static final Instant START = Instant.parse("2026-11-26T09:00:00Z");

    private Instant now = START;

    private final MetaRateLimiter limiter = new MetaRateLimiter(new MovingClock());

    @Test
    void refuses_the_call_past_the_limit_with_a_wait_no_longer_than_the_window() {
        callTheLimit("203.0.113.7");

        assertThatThrownBy(() -> limiter.requireAllowed("203.0.113.7"))
                .isInstanceOf(RateLimitedException.class)
                .satisfies(
                        refusal -> {
                            Duration wait = ((RateLimitedException) refusal).retryAfter();
                            assertThat(wait).isPositive();
                            assertThat(wait).isLessThanOrEqualTo(MetaRateLimiter.WINDOW);
                        });
    }

    @Test
    void the_window_rolls_so_the_refusal_lifts_on_its_own() {
        callTheLimit("203.0.113.7");
        assertThatThrownBy(() -> limiter.requireAllowed("203.0.113.7"))
                .isInstanceOf(RateLimitedException.class);

        now = now.plus(MetaRateLimiter.WINDOW);

        assertThatCode(() -> limiter.requireAllowed("203.0.113.7")).doesNotThrowAnyException();
    }

    @Test
    void one_address_over_the_limit_does_not_throttle_another() {
        callTheLimit("203.0.113.7");

        assertThatCode(() -> limiter.requireAllowed("198.51.100.4")).doesNotThrowAnyException();
    }

    @Test
    void at_the_cap_expired_windows_are_dropped_rather_than_grown() {
        for (int caller = 0; caller < 10_000; caller++) {
            limiter.requireAllowed("sweep-" + caller);
        }
        now = now.plus(MetaRateLimiter.WINDOW);

        limiter.requireAllowed("198.51.100.4");

        assertThat(limiter.trackedCallers()).isEqualTo(1);
    }

    @Test
    void at_the_cap_a_live_window_is_kept_so_a_sweep_cannot_reset_it() {
        callTheLimit("203.0.113.7");
        for (int caller = 0; caller < 10_000; caller++) {
            limiter.requireAllowed("sweep-" + caller);
        }

        assertThatThrownBy(() -> limiter.requireAllowed("203.0.113.7"))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void clearing_forgets_every_window() {
        callTheLimit("203.0.113.7");

        limiter.clearAll();

        assertThatCode(() -> limiter.requireAllowed("203.0.113.7")).doesNotThrowAnyException();
    }

    private void callTheLimit(String address) {
        for (int call = 1; call <= MetaRateLimiter.PERMITTED_PER_WINDOW; call++) {
            limiter.requireAllowed(address);
        }
    }

    /** Reads {@link #now}, so a test moves time by assigning to it. */
    private final class MovingClock extends Clock {

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
