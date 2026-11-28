package com.budgetowl.imports.service;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The bounds on how much parsing one caller can cause (rule 3), in two steps. {@link #admit},
 * before the body is read: the rate limit, then this user's slot. {@link #parse}, after: an
 * instance slot.
 */
@Component
public class ImportThrottle {

    private final ImportRateLimiter rateLimiter;
    private final ImportGate gate;

    public ImportThrottle(ImportRateLimiter rateLimiter, ImportGate gate) {
        this.rateLimiter = rateLimiter;
        this.gate = gate;
    }

    /**
     * Before the body is read: counts the request against the rate limit, then takes this user's
     * slot. The instance's slots are not touched, so a slow upload costs only its sender.
     *
     * @throws com.budgetowl.common.RateLimitedException {@code 429} with {@code Retry-After}
     */
    public Permit admit(UUID userId) {
        rateLimiter.requireAllowed(userId);
        return new Permit(gate.enterUser(userId));
    }

    /**
     * After the body is read: one of the instance's two parse slots.
     *
     * @throws com.budgetowl.common.RateLimitedException {@code 429} with {@code Retry-After}
     */
    public Permit parse() {
        return new Permit(gate.enterInstance());
    }

    /** Releases the gate when the request ends, however it ends. */
    public static final class Permit implements AutoCloseable {

        private final ImportGate.Permit inner;

        private Permit(ImportGate.Permit inner) {
            this.inner = inner;
        }

        @Override
        public void close() {
            inner.close();
        }
    }
}
