package com.budgetowl.debt.service;

import com.budgetowl.common.RateLimitedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Bounds how often one caller may ask the solver to work.
 *
 * <p><b>{@code POST /api/debt-terms/solve} is the first endpoint in this app whose cost depends on
 * the numbers in the request</b> (docs/features/debt-plan.md, Security considerations). One call is
 * already bounded — 600 months of iteration × 64 bisection steps, about 38,400 {@code BigDecimal}
 * steps — and that is the per-call control. This is the per-caller one: a loop of requests chosen
 * to maximise bisection depth is sustained CPU burn on a machine in somebody's house.
 *
 * <p><b>Why not {@code AuthRateLimiter}.</b> That one exists to slow down guessing, so it counts
 * <em>failures</em> and backs off exponentially from the fifth — which is right for a login and
 * wrong here, where a member filling in a terms editor legitimately makes a run of successful
 * calls. This is a plain fixed window: generous for anyone typing, and a ceiling for anyone
 * looping. Same refusal ({@link RateLimitedException}, {@code 429} with {@code Retry-After}), same
 * promise that it is never a permanent lock.
 *
 * <p><b>Accepted risk:</b> in memory and per instance, as {@code AuthRateLimiter} already
 * documents. Adequate for one household on one box; it would not survive a multi-instance
 * deployment, which this product does not have (ADR-0026).
 */
@Component
public class TermsSolveRateLimiter {

    /** Solves one caller may make per {@link #WINDOW}. A person typing cannot reach it. */
    public static final int PERMITTED_PER_WINDOW = 60;

    public static final Duration WINDOW = Duration.ofMinutes(1);

    /** Beyond this many tracked callers the expired windows are dropped rather than grown. */
    private static final int MAXIMUM_TRACKED_CALLERS = 10_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public TermsSolveRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Counts this call and refuses it if the caller is over the limit.
     *
     * @param callerKey the authenticated user's id. Never an amount, and never the request's
     *     numbers — a rate-limit key outlives the request
     * @throws RateLimitedException with the finite wait until the window rolls
     */
    public void requireAllowed(String callerKey) {
        Instant now = clock.instant();
        prune(now);
        Window window =
                windows.compute(
                        callerKey,
                        (ignored, current) ->
                                current == null || !current.endsAfter(now)
                                        ? new Window(now.plus(WINDOW), 1)
                                        : current.plusOne());
        if (window.calls() > PERMITTED_PER_WINDOW) {
            throw new RateLimitedException(Duration.between(now, window.endsAt()));
        }
    }

    /** Forgets every window. No HTTP route reaches this; the test suite and a shell do. */
    public void clearAll() {
        windows.clear();
    }

    private void prune(Instant now) {
        if (windows.size() < MAXIMUM_TRACKED_CALLERS) {
            return;
        }
        windows.values().removeIf(window -> !window.endsAfter(now));
    }

    private record Window(Instant endsAt, int calls) {

        boolean endsAfter(Instant now) {
            return endsAt.isAfter(now);
        }

        Window plusOne() {
            return new Window(endsAt, calls + 1);
        }
    }
}
