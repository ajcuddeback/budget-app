package com.budgetowl.auth.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends a session once it is older than the absolute cap, however busy it has been.
 *
 * <p>The idle timeout is Spring Session's ({@code spring.session.timeout}); it has no notion of an
 * absolute one, and an idle timeout alone means a stolen cookie that is used every twenty minutes
 * lasts forever. Both are required by docs/architecture/security-model.md and both are enforced
 * server-side — clearing a cookie the attacker already copied achieves nothing.
 *
 * <p>Expiry is silent: the session is invalidated, the context cleared, and the request carries on
 * to be refused with the same {@code 401} as any other unauthenticated one.
 */
public class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

    private final Duration absoluteTimeout;
    private final Clock clock;

    public AbsoluteSessionTimeoutFilter(Duration absoluteTimeout, Clock clock) {
        this.absoluteTimeout = absoluteTimeout;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && hasOutlivedTheCap(session)) {
            session.invalidate();
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }

    private boolean hasOutlivedTheCap(HttpSession session) {
        Instant createdAt = Instant.ofEpochMilli(session.getCreationTime());
        return createdAt.plus(absoluteTimeout).isBefore(clock.instant());
    }
}
