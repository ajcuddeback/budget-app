package com.budgetowl.auth.web;

import com.budgetowl.auth.domain.AuthenticatedUser;
import com.budgetowl.auth.service.DeviceService;
import com.budgetowl.auth.service.TransportAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Starting and ending a web session, in one place.
 *
 * <p><b>The session id is rotated on login</b> by the {@link SessionAuthenticationStrategy}
 * configured in {@code SecurityConfig} — this is where session fixation is actually prevented, and
 * it is why an attacker cannot plant a session id before login and reuse it afterwards. There is a
 * test that logs in twice on one cookie and asserts the id changed.
 *
 * <p>Logout invalidates the session <b>server-side</b>. Clearing the cookie is not enough: the
 * attacker who copied it does not obey a {@code Set-Cookie} telling them to forget it.
 */
@Component
public class SessionLogin {

    private final SecurityContextRepository contextRepository;
    private final SessionAuthenticationStrategy sessionStrategy;

    public SessionLogin(
            SecurityContextRepository contextRepository,
            SessionAuthenticationStrategy sessionStrategy) {
        this.contextRepository = contextRepository;
        this.sessionStrategy = sessionStrategy;
    }

    /**
     * @param deviceLabel what this browser is called on the user's devices screen
     */
    public void start(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticatedUser user,
            String deviceLabel) {
        TransportAuthentication authentication = TransportAuthentication.session(user);
        sessionStrategy.onAuthentication(authentication, request, response);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        request.getSession().setAttribute(DeviceService.LABEL_ATTRIBUTE, deviceLabel);
    }

    public void end(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }
}
