package com.budgetowl.debt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.budgetowl.common.RateLimitedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The per-caller bound on the solve endpoint — the one endpoint whose cost depends on the numbers
 * in the request.
 *
 * <p>Unit-tested with a clock the test controls, because the properties that matter are about
 * <b>time</b>: that the window rolls, that the refusal is never permanent, and that one caller's
 * loop does not throttle another's editor. Waiting a minute to find out is not a test anybody runs.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TermsSolveRateLimiterTest {

    private static final Instant START = Instant.parse("2026-11-24T09:00:00Z");

    /** Moved by the tests. The limiter reads the clock itself, so this is how time passes here. */
    private Instant now = START;

    @Test
    void permits_a_run_of_calls_a_person_could_actually_make() {
        TermsSolveRateLimiter moving = movingLimiter();

        assertThatCode(
                        () -> {
                            for (int call = 1;
                                    call <= TermsSolveRateLimiter.PERMITTED_PER_WINDOW;
                                    call++) {
                                moving.requireAllowed("ada");
                            }
                        })
                .doesNotThrowAnyException();
    }

    @Test
    void refuses_the_call_past_the_limit_with_a_finite_wait() {
        TermsSolveRateLimiter moving = movingLimiter();
        for (int call = 1; call <= TermsSolveRateLimiter.PERMITTED_PER_WINDOW; call++) {
            moving.requireAllowed("ada");
        }

        assertThatThrownBy(() -> moving.requireAllowed("ada"))
                .isInstanceOf(RateLimitedException.class)
                .satisfies(
                        refusal -> {
                            Duration wait = ((RateLimitedException) refusal).retryAfter();
                            // Never a permanent lock: a refusal that does not lift is a denial of
                            // service against the real household.
                            assertThat(wait).isPositive();
                            assertThat(wait).isLessThanOrEqualTo(TermsSolveRateLimiter.WINDOW);
                        });
    }

    @Test
    void the_window_rolls_so_the_refusal_lifts_on_its_own() {
        TermsSolveRateLimiter moving = movingLimiter();
        for (int call = 1; call <= TermsSolveRateLimiter.PERMITTED_PER_WINDOW + 1; call++) {
            try {
                moving.requireAllowed("ada");
            } catch (RateLimitedException expected) {
                assertThat(expected.retryAfter()).isPositive();
            }
        }

        now = now.plus(TermsSolveRateLimiter.WINDOW).plusSeconds(1);

        assertThatCode(() -> moving.requireAllowed("ada")).doesNotThrowAnyException();
    }

    @Test
    void one_callers_loop_does_not_throttle_another_callers_editor() {
        TermsSolveRateLimiter moving = movingLimiter();
        for (int call = 1; call <= TermsSolveRateLimiter.PERMITTED_PER_WINDOW + 1; call++) {
            try {
                moving.requireAllowed("ada");
            } catch (RateLimitedException expected) {
                // Ada is over the limit, which is the point.
            }
        }

        // Grace is a different member of the same household, and a shared limit would make one
        // person's mistake the other person's outage.
        assertThatCode(() -> moving.requireAllowed("grace")).doesNotThrowAnyException();
    }

    @Test
    void clearing_forgets_every_window() {
        TermsSolveRateLimiter moving = movingLimiter();
        for (int call = 1; call <= TermsSolveRateLimiter.PERMITTED_PER_WINDOW + 1; call++) {
            try {
                moving.requireAllowed("ada");
            } catch (RateLimitedException expected) {
                // Expected on the last one.
            }
        }

        moving.clearAll();

        // There is no HTTP route to this and there must not be — an attacker who could clear the
        // window would not be rate-limited at all. It exists for the host shell and for the test
        // suite, which runs many solves against one instance.
        assertThatCode(() -> moving.requireAllowed("ada")).doesNotThrowAnyException();
    }

    /** A limiter over a clock this test moves. */
    private TermsSolveRateLimiter movingLimiter() {
        return new TermsSolveRateLimiter(new MovingClock());
    }

    /** Reads {@link #now}, so a test moves time by assigning to it. */
    private final class MovingClock extends Clock {

        @Override
        public java.time.ZoneId getZone() {
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
    }
}
