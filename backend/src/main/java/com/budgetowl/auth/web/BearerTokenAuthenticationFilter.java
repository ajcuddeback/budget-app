package com.budgetowl.auth.web;

import com.budgetowl.auth.service.AuthTokenService;
import com.budgetowl.auth.service.TransportAuthentication;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
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

    /** Whether a request carries a bearer credential, used to exempt it from CSRF. */
    public static boolean isBearerRequest(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.regionMatches(true, 0, SCHEME, 0, SCHEME.length());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Authentication is attempted for every request that does not already have it, and the
        // header decides only what credential is presented — not whether the attempt happens.
        // Nothing about the caller's input can steer the filter around its own authentication
        // step, which is what CodeQL's user-controlled-bypass rule is looking for. A request with
        // no bearer header presents an empty credential, which AuthTokenService rejects without
        // touching the database, so this costs a non-bearer request nothing.
        boolean authenticatedByToken = false;
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            Optional<TransportAuthentication> authentication =
                    tokens.authenticate(bearerCredential(request));
            if (authentication.isPresent()) {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication.get());
                SecurityContextHolder.setContext(context);
                authenticatedByToken = true;
            }
        }
        // A bearer request must not leave a session behind. Setting the context here was enough for
        // SessionManagementFilter downstream to treat it as a fresh authentication and persist it
        // through HttpSessionSecurityContextRepository, which created a real Spring Session and
        // returned it as Set-Cookie: BUDGETOWL_SESSION. That cookie was a second, independent
        // credential for the same user — and revoking the token did not touch it, so a revoked or
        // logged-out token still had a live session with OWNER write access until it idled out.
        // It also minted one session row per request, which buries the devices list in
        // indistinguishable "Web browser" entries and grows spring_session without bound.
        //
        // Creation is blocked rather than the session deleted afterwards: by the time one exists
        // its cookie is already on the response. An existing session stays readable, so a browser
        // request that also carries a token is unaffected.
        chain.doFilter(authenticatedByToken ? withoutSessionCreation(request) : request, response);
    }

    /**
     * The credential from an {@code Authorization: Bearer} header, or {@code ""} if there is none.
     */
    private static String bearerCredential(HttpServletRequest request) {
        if (!isBearerRequest(request)) {
            return "";
        }
        return request.getHeader(HttpHeaders.AUTHORIZATION).substring(SCHEME.length()).strip();
    }

    /**
     * The same request, but nothing downstream can start a session on it. An existing one is still
     * returned, so this withholds a new credential rather than breaking a request that already had
     * one.
     */
    private static HttpServletRequest withoutSessionCreation(HttpServletRequest request) {
        return new HttpServletRequestWrapper(request) {
            @Override
            public HttpSession getSession(boolean create) {
                return super.getSession(false);
            }

            @Override
            public HttpSession getSession() {
                return super.getSession(false);
            }
        };
    }
}
