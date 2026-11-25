package com.budgetowl.imports.web;

import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.household.service.MembershipService;
import com.budgetowl.imports.service.ImportThrottle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Admits an import request — role, rate limit, this user's slot — <b>before its body is read</b>.
 * The instance's two parse slots are taken afterwards, in {@code ImportService}: taking one here
 * let two writers dripping slow uploads stop every household importing (slice-12 security
 * re-audit).
 *
 * <p>Slice-12 security audit, medium 2: the role check and the throttle used to run in {@code
 * ImportService}, after Jackson had already turned the whole base64 body into a string. A signed-in
 * {@code VIEWER} could hold many slow seven-megabyte bodies open at once — refused only after each
 * was in memory, and never rate-limited, because the role check came before the limiter. With
 * virtual threads there is no thread pool to run out first.
 *
 * <p>A {@code HandlerInterceptor}'s {@code preHandle} runs after Spring Security has authenticated
 * the caller and before argument resolution reads the {@code @RequestBody}; an exception it throws
 * goes through {@code ApiExceptionHandler} like any other, so the {@code 403} and the {@code 429}
 * with {@code Retry-After} are the same responses they always were. The user's permit is held for
 * the whole request — reading, parsing, classifying, writing — and released in {@code
 * afterCompletion}, however the request ends.
 *
 * <p>{@code ImportService} still checks the role itself. That is cheap, and it keeps the service
 * safe for any caller that is not this controller.
 */
public final class ImportAdmissionInterceptor implements HandlerInterceptor {

    private static final String PERMIT = ImportAdmissionInterceptor.class.getName() + ".permit";

    private final MembershipService memberships;
    private final ImportThrottle throttle;

    public ImportAdmissionInterceptor(MembershipService memberships, ImportThrottle throttle) {
        this.memberships = memberships;
        this.throttle = throttle;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        Optional<TransportAuthentication> caller = TransportAuthentication.current();
        if (caller.isEmpty()) {
            // Spring Security refuses an unauthenticated request before it gets here, and today
            // only
            // TransportAuthentication is ever put in the context. Refused rather than waved
            // through, so a future authentication type cannot skip the limits without anyone
            // noticing.
            throw new AuthenticationCredentialsNotFoundException("no transport authentication");
        }
        memberships.requireWriteAccess(caller.get().userId());
        // Last, so that a refusal above never holds a permit. This user's slot only: the
        // instance's slots are taken after the body is read, in ImportService, so a slow upload
        // can never sit on one.
        request.setAttribute(PERMIT, throttle.admit(caller.get().userId()));
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception failure) {
        if (request.getAttribute(PERMIT) instanceof ImportThrottle.Permit permit) {
            request.removeAttribute(PERMIT);
            permit.close();
        }
    }
}
