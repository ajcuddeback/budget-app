package com.budgetowl.auth.web;

import com.budgetowl.auth.service.AuthTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates the mobile transport: {@code Authorization: Bearer <token>} (ADR-0018).
 *
 * <p>The token is looked up by its SHA-256 on every request, so revocation and expiry are read from
 * the row rather than trusted from the credential — a revoked token stops working on the very next
 * request, which is the entire reason these are opaque rather than self-contained.
 *
 * <p>An absent, malformed, unknown, revoked or expired token all do the same thing: nothing. The
 * request continues unauthenticated and the entry point answers {@code 401}. This filter never
 * writes a response, never distinguishes the cases and <b>never logs the header value</b> — an
 * {@code Authorization} header in a log is a working credential in a log.
 *
 * <p>Not a {@code @Component}: Spring Boot registers every {@code Filter} bean with the servlet
 * container as well, which would run it twice — once outside the security chain, where the security
 * context is not managed.
 */
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String SCHEME = "Bearer ";

    private final AuthTokenService tokens;

    public BearerTokenAuthenticationFilter(AuthTokenService tokens) {
        this.tokens = tokens;
    }

    /**
     * Anonymous counts as "nobody yet".
     *
     * <p>{@code AnonymousAuthenticationFilter} puts a non-null {@code Authentication} in the
     * context for every unauthenticated request, so a null check alone silently skips this filter
     * and every bearer request is refused with a 401 that looks exactly like a bad token.
     */
    private static boolean notYetAuthenticated() {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        return current == null || current instanceof AnonymousAuthenticationToken;
    }

    /** Whether a request carries a bearer credential, used to exempt it from CSRF. */
    public static boolean isBearerRequest(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.regionMatches(true, 0, SCHEME, 0, SCHEME.length());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isBearerRequest(request)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String presented =
                    request.getHeader(HttpHeaders.AUTHORIZATION).substring(SCHEME.length()).strip();
            if (!presented.isEmpty()) {
                tokens.authenticate(presented)
                        .ifPresent(
                                authentication -> {
                                    SecurityContext context =
                                            SecurityContextHolder.createEmptyContext();
                                    context.setAuthentication(authentication);
                                    SecurityContextHolder.setContext(context);
                                });
            }
        }
        chain.doFilter(request, response);
    }
}
