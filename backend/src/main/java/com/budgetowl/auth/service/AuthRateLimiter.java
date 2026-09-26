package com.budgetowl.auth.service;

import com.budgetowl.common.RateLimitedException;
import com.budgetowl.config.SecurityProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Throttles login, bearer-token issue and invitation acceptance — <b>per IP and per email</b>, with
 * exponential backoff and <b>never a permanent lock</b>
 * (docs/features/authentication-and-households.md).
 *
 * <p>Both axes are needed and neither is sufficient. Per email alone lets an attacker spray one
 * password across every address they can guess; per IP alone lets a botnet brute-force one account
 * from a thousand addresses. A permanent lock would be the worst outcome of all: anyone who knows a
 * member's address could lock them out of their own financial records, on their own hardware, with
 * no support desk to call.
 *
 * <p>The key is whatever was <em>submitted</em>, never whether it matched anything. An email that
 * does not exist is throttled exactly like one that does, or the throttle itself answers the
 * question the constant-response login refuses to.
 *
 * <p><b>Accepted risk:</b> in-memory and per-instance, as recorded in the feature doc. That is
 * adequate for one household on one box and would not survive a multi-instance deployment, which
 * this product does not have.
 */
@Component
public class AuthRateLimiter {

    /** Beyond this many tracked keys the oldest finished ones are dropped rather than grown. */
    private static final int MAXIMUM_TRACKED_KEYS = 10_000;

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final SecurityProperties.RateLimit config;
    private final Clock clock;

    public AuthRateLimiter(SecurityProperties properties, Clock clock) {
        this.config = properties.rateLimit();
        this.clock = clock;
    }

    public static String ip(String address) {
        return "ip:" + address;
    }

    public static String email(String address) {
        return "email:" + address.strip().toLowerCase(Locale.ROOT);
    }

    /** Keyed by the token's hash, never by the token: a rate-limit key outlives the request. */
    public static String tokenHash(String hash) {
        return "token:" + hash;
    }

    /**
     * @throws RateLimitedException while any of the keys is backing off
     */
    public void requireAllowed(List<String> keys) {
        Instant now = clock.instant();
        Duration longestWait = Duration.ZERO;
        for (String key : keys) {
            Attempts tracked = attempts.get(key);
            if (tracked != null && tracked.lockedUntil.isAfter(now)) {
                Duration wait = Duration.between(now, tracked.lockedUntil);
                if (wait.compareTo(longestWait) > 0) {
                    longestWait = wait;
                }
            }
        }
        if (!longestWait.isZero()) {
            throw new RateLimitedException(longestWait);
        }
    }

    public void recordFailure(List<String> keys) {
        Instant now = clock.instant();
        prune(now);
        for (String key : keys) {
            attempts.compute(
                    key,
                    (ignored, previous) -> {
                        int failures = carriedOver(previous, now) + 1;
                        return new Attempts(failures, now.plus(backoffAfter(failures)));
                    });
        }
    }

    /**
     * Failures are consecutive, not cumulative for life: once a key has sat past its backoff by the
     * maximum delay again, the count starts over. Otherwise a member who mistypes a password once a
     * month eventually finds themselves throttled by history.
     */
    private int carriedOver(Attempts previous, Instant now) {
        if (previous == null) {
            return 0;
        }
        return previous.lockedUntil.plus(config.maximumDelay()).isBefore(now)
                ? 0
                : previous.failures;
    }

    /**
     * Forgets every tracked key.
     *
     * <p>There is no HTTP route to this and there must not be: an attacker who could clear the
     * backoff would not be rate-limited at all. It exists because a backoff sometimes has to be
     * lifted from the host shell — a member locked out by somebody spraying their address should
     * not have to wait — and because the test suite runs many logins against one instance.
     */
    public void clearAll() {
        attempts.clear();
    }

    /** A successful attempt clears the backoff on every key it used. */
    public void recordSuccess(List<String> keys) {
        keys.forEach(attempts::remove);
    }

    /**
     * Zero until the permitted failures are used up, then doubling, then capped. The cap is what
     * keeps this a slowdown rather than a lockout.
     */
    private Duration backoffAfter(int failures) {
        int excess = failures - config.permittedFailures();
        if (excess <= 0) {
            return Duration.ZERO;
        }
        Duration delay = config.initialDelay();
        for (int doubling = 1; doubling < excess; doubling++) {
            delay = delay.multipliedBy(2);
            if (delay.compareTo(config.maximumDelay()) >= 0) {
                return config.maximumDelay();
            }
        }
        return delay.compareTo(config.maximumDelay()) > 0 ? config.maximumDelay() : delay;
    }

    private void prune(Instant now) {
        if (attempts.size() < MAXIMUM_TRACKED_KEYS) {
            return;
        }
        attempts.values().removeIf(tracked -> tracked.lockedUntil.isBefore(now));
    }

    private record Attempts(int failures, Instant lockedUntil) {}
}
