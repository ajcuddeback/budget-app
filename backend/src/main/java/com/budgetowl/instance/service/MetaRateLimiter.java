package com.budgetowl.instance.service;

import com.budgetowl.common.RateLimitedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Bounds how often one address may ask {@code GET /api/meta} who this server is.
 *
 * <p>The route is public, takes no input and costs almost nothing, so the limit is not protecting
 * CPU. It is there because <b>every public route is rate-limited</b> (docs/features/mobile-app.md):
 * an unauthenticated endpoint that answers as fast as it is asked is a free probe for anybody
 * sweeping address ranges for self-hosted finance servers, and "rate-limited like the others" is
 * the promise the public-route list makes.
 *
 * <p><b>Why not {@code AuthRateLimiter}.</b> That one counts <em>failures</em> and backs off, which
 * is right for a guess at a password. A meta call never fails, so it would never be limited at all.
 * This is the plain fixed window {@code TermsSolveRateLimiter} already uses: generous for a phone
 * that checks its server on every launch, a ceiling for a loop. Same refusal ({@link
 * RateLimitedException}, {@code 429} with {@code Retry-After}), never a permanent lock.
 *
 * <p><b>Accepted risk:</b> in memory and per instance, as the other limiters document. Adequate for
 * one household on one box (ADR-0026).
 */
@Component
public class MetaRateLimiter {

    /** Calls one address may make per {@link #WINDOW}. An app checks once per launch or connect. */
    public static final int PERMITTED_PER_WINDOW = 60;

    public static final Duration WINDOW = Duration.ofMinutes(1);

    /** Beyond this many tracked addresses the expired windows are dropped rather than grown. */
    private static final int MAXIMUM_TRACKED_CALLERS = 10_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public MetaRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Counts this call and refuses it if the address is over the limit.
     *
     * @param clientAddress the caller's address — the only key a public route has
     * @throws RateLimitedException with the finite wait until the window rolls
     */
    public void requireAllowed(String clientAddress) {
        Instant now = clock.instant();
        prune(now);
        Window window =
                windows.compute(
                        clientAddress,
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
