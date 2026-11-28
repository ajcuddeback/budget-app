package com.budgetowl.imports.service;

import com.budgetowl.common.RateLimitedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * At most 30 previews plus commits per user per ten minutes (rule 3).
 *
 * <p>The shape of {@code TermsSolveRateLimiter}: a fixed window, in memory, per instance, keyed by
 * the authenticated user's id. Previews and commits count together because both parse the whole
 * file. A person working through a mapping by hand makes a handful; a script pinning the CPU makes
 * thirty and stops.
 *
 * <p><b>Accepted risk</b>, as for the other two limiters: a restart resets it, and it would not
 * survive a multi-instance deployment, which this product does not have (ADR-0026).
 */
@Component
public class ImportRateLimiter {

    public static final int PERMITTED_PER_WINDOW = 30;

    public static final Duration WINDOW = Duration.ofMinutes(10);

    private static final int MAXIMUM_TRACKED_CALLERS = 10_000;

    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public ImportRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param userId the authenticated user — never anything from the request
     * @throws RateLimitedException with the finite wait until the window rolls
     */
    public void requireAllowed(UUID userId) {
        Instant now = clock.instant();
        prune(now);
        Window window =
                windows.compute(
                        userId,
                        (ignored, current) ->
                                current == null || !current.endsAt().isAfter(now)
                                        ? new Window(now.plus(WINDOW), 1)
                                        : new Window(current.endsAt(), current.calls() + 1));
        if (window.calls() > PERMITTED_PER_WINDOW) {
            throw new RateLimitedException(Duration.between(now, window.endsAt()));
        }
    }

    /** Forgets every window. No HTTP route reaches this; the test suite does. */
    public void clearAll() {
        windows.clear();
    }

    private void prune(Instant now) {
        if (windows.size() < MAXIMUM_TRACKED_CALLERS) {
            return;
        }
        windows.values().removeIf(window -> !window.endsAt().isAfter(now));
    }

    private record Window(Instant endsAt, int calls) {}
}
