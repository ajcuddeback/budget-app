package com.budgetowl.instance.service;

import org.springframework.stereotype.Service;

/**
 * "Is this a Budget Owl server, and which API does it speak?" — the one question a phone asks
 * before anybody has signed in (ADR-0042).
 *
 * <p>The answer is a constant. <b>It must stay one</b>: no build number, commit, hostname, runtime
 * version, user count or setup state. Each of those fingerprints an instance — tells a stranger
 * which known bug this box has, or that a household lives here — and none of them helps a client
 * decide whether it can talk to the server. {@code MetaIT} pins the exact body, so adding a field
 * fails a test rather than a review.
 */
@Service
public class InstanceMetaService {

    /** The identity every Budget Owl server answers with. Never derived from configuration. */
    public static final String APP = "budget-owl";

    /**
     * The API this server speaks. Bumped only when a change would break a client written against
     * the previous one — not per release, which is what would make it a fingerprint.
     */
    public static final int API_VERSION = 1;

    /** The oldest client API this server still serves. A client below it is told to update. */
    public static final int MINIMUM_CLIENT_API_VERSION = 1;

    private final MetaRateLimiter limiter;

    public InstanceMetaService(MetaRateLimiter limiter) {
        this.limiter = limiter;
    }

    /**
     * @param clientAddress the caller's address, for the rate limit only — it is not logged here
     * @throws com.budgetowl.common.RateLimitedException when the address is over its limit
     */
    public InstanceMeta describe(String clientAddress) {
        limiter.requireAllowed(clientAddress);
        return new InstanceMeta(APP, API_VERSION, MINIMUM_CLIENT_API_VERSION);
    }

    /** What {@link #describe} answers. Three fields, and a test fails if there is a fourth. */
    public record InstanceMeta(String app, int apiVersion, int minimumClientApiVersion) {}
}
