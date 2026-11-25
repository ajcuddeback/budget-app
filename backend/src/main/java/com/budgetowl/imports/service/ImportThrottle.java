package com.budgetowl.imports.service;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The two bounds on how much parsing one caller can cause, applied together and in order: the rate
 * limit counts the request, then the concurrency gate admits it or refuses it (rule 3).
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
     * @throws com.budgetowl.common.RateLimitedException {@code 429} with {@code Retry-After}
     */
    public Permit enter(UUID userId) {
        rateLimiter.requireAllowed(userId);
        return new Permit(gate.enter(userId));
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
