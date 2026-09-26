package com.budgetowl.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The knobs on authentication, all of them with a safe default in {@code application.yml} so a
 * self-hoster's first run needs nothing beyond a database password (ADR-0016).
 *
 * <p>No secret appears here, and none may: secrets come from the environment with no fallback
 * (docs/architecture/security-model.md), and the OIDC client secret an operator types in lives in
 * {@code instance_settings} rather than in configuration.
 *
 * @param bcryptStrength BCrypt cost factor, at least 12 in any deployment. Lower only in tests,
 *     where 250ms per login turns a suite into something nobody runs.
 * @param session web session lifetimes
 * @param token mobile bearer token lifetimes (ADR-0018)
 * @param rateLimit login, token issue and invitation acceptance throttling
 */
@ConfigurationProperties("budgetowl.security")
public record SecurityProperties(
        int bcryptStrength, Session session, Token token, RateLimit rateLimit) {

    /**
     * @param absoluteTimeout the cap a session cannot outlive however active it is. Spring Session
     *     has only an idle timeout ({@code spring.session.timeout}), so this one is enforced by
     *     {@code AbsoluteSessionTimeoutFilter}.
     */
    public record Session(Duration absoluteTimeout) {}

    /**
     * @param idleTimeout sliding expiry, extended on use
     * @param absoluteTimeout the cap the sliding expiry may never push past
     */
    public record Token(Duration idleTimeout, Duration absoluteTimeout) {}

    /**
     * @param permittedFailures consecutive failures allowed before backoff begins
     * @param initialDelay the first backoff, doubled per failure after that
     * @param maximumDelay the cap. <b>Finite, always</b> — a permanent lock is a denial of service
     *     against the real user, carried out by anyone who knows their email address.
     */
    public record RateLimit(int permittedFailures, Duration initialDelay, Duration maximumDelay) {}
}
