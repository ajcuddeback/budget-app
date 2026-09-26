package com.budgetowl.common;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Too many attempts, for now.
 *
 * <p><b>Never a permanent lock</b> (docs/features/authentication-and-households.md): a lock that
 * does not lift is a denial of service against the real user, carried out by anybody who knows
 * their email address. {@link #retryAfter()} is always finite and is rendered into the {@code
 * Retry-After} header.
 */
public class RateLimitedException extends DomainException {

    private static final long serialVersionUID = 1L;

    private final Duration retryAfter;

    public RateLimitedException(Duration retryAfter) {
        super(
                ErrorCode.RATE_LIMITED,
                "too many attempts",
                Map.of("retryAfterSeconds", Objects.requireNonNull(retryAfter).toSeconds()));
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
