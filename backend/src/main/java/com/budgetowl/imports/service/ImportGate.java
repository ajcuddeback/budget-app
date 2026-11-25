package com.budgetowl.imports.service;

import com.budgetowl.common.RateLimitedException;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/**
 * How many files are being parsed at once: one per user, two per instance (rule 3, "Concurrent
 * parses").
 *
 * <p>Two per instance because the instance may be a single-board computer: {@code
 * backend/Dockerfile} gives the JVM 75% of RAM with the serial collector, and one parse holds a few
 * times the file's size in heap while it decodes. One per user because a member scripting previews
 * in a loop should be able to take one slot, not both.
 *
 * <p><b>Never waits.</b> A busy gate refuses at once with {@code 429} and a short {@code
 * Retry-After}; queueing requests behind a parse would hold request threads and their memory for as
 * long as the queue is, which is the exhaustion this exists to prevent.
 */
@Component
public class ImportGate {

    /** Per instance (rule 3). */
    public static final int INSTANCE_PERMITS = 2;

    /** What a refused caller is told to wait: about one parse, at the deadline's worst case. */
    static final Duration RETRY_AFTER = Duration.ofSeconds(15);

    private final Semaphore instance = new Semaphore(INSTANCE_PERMITS);
    private final Set<UUID> parsing = ConcurrentHashMap.newKeySet();

    /**
     * @throws RateLimitedException when this user already has a parse running, or the instance has
     *     two
     */
    public Permit enter(UUID userId) {
        if (!parsing.add(userId)) {
            throw new RateLimitedException(RETRY_AFTER);
        }
        if (!instance.tryAcquire()) {
            parsing.remove(userId);
            throw new RateLimitedException(RETRY_AFTER);
        }
        return new Permit(this, userId);
    }

    private void leave(UUID userId) {
        instance.release();
        parsing.remove(userId);
    }

    /** Held for one parse; released exactly once, however the request ends. */
    public static final class Permit implements AutoCloseable {

        private final ImportGate gate;
        private final UUID userId;
        private boolean released;

        private Permit(ImportGate gate, UUID userId) {
            this.gate = gate;
            this.userId = userId;
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                gate.leave(userId);
            }
        }
    }
}
