package com.budgetowl.auth.web;

import com.budgetowl.auth.domain.AuthenticatedUser;
import com.budgetowl.auth.domain.CredentialTransport;
import com.budgetowl.auth.service.AuthTokenService;
import com.budgetowl.auth.service.AuthenticationService;
import com.budgetowl.auth.service.IssuedToken;
import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.auth.web.AuthRequests.IssueTokenRequest;
import com.budgetowl.auth.web.AuthRequests.LoginRequest;
import com.budgetowl.auth.web.AuthResponses.CurrentUserResponse;
import com.budgetowl.auth.web.AuthResponses.MembershipResponse;
import com.budgetowl.auth.web.AuthResponses.TokenResponse;
import com.budgetowl.common.web.ClientAddress;
import com.budgetowl.household.service.CurrentHousehold;
import com.budgetowl.household.service.HouseholdService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signing in and out, on both transports (ADR-0018).
 *
 * <p>Same credentials, same user store, same authorization — the only difference is what the caller
 * gets back: a rotated session for a browser, an opaque token for a phone. Both are server-side and
 * revocable.
 *
 * <p>This controller makes no authorization decision and no authentication decision. It validates a
 * body, hands it to a service, and turns what comes back into a response. The <b>acting user is
 * read from the {@code SecurityContext}</b> and never from the request.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final int MAXIMUM_LABEL_LENGTH = 100;
    private static final String UNLABELLED_BROWSER = "Web browser";

    private final AuthenticationService authentication;
    private final AuthTokenService tokens;
    private final HouseholdService households;
    private final SessionLogin sessionLogin;

    public AuthController(
            AuthenticationService authentication,
            AuthTokenService tokens,
            HouseholdService households,
            SessionLogin sessionLogin) {
        this.authentication = authentication;
        this.tokens = tokens;
        this.households = households;
        this.sessionLogin = sessionLogin;
    }

    /** Public and rate-limited. The response is identical for every kind of failure. */
    @PostMapping("/login")
    CurrentUserResponse login(
            @Valid @RequestBody LoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        AuthenticatedUser user =
                authentication.authenticate(
                        body.email(),
                        body.password(),
                        ClientAddress.of(request),
                        CredentialTransport.SESSION);
        sessionLogin.start(request, response, user, browserLabel(request));
        return currentUser(user);
    }

    /** Public and rate-limited. The mobile equivalent of {@code /login}. */
    @PostMapping("/token")
    @ResponseStatus(HttpStatus.CREATED)
    TokenResponse issueToken(
            @Valid @RequestBody IssueTokenRequest body, HttpServletRequest request) {
        String clientIp = ClientAddress.of(request);
        AuthenticatedUser user =
                authentication.authenticate(
                        body.email(), body.password(), clientIp, CredentialTransport.BEARER);
        IssuedToken issued = tokens.issue(user.userId(), body.deviceLabel(), clientIp);
        return new TokenResponse(
                issued.id(), issued.value(), issued.deviceLabel(), issued.expiresAt());
    }

    /**
     * Ends whichever credential made the call: the session is invalidated server-side, or the
     * bearer token is revoked. Both take effect on the caller's very next request.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(
            TransportAuthentication caller,
            HttpServletRequest request,
            HttpServletResponse response) {
        caller.tokenId().ifPresent(tokenId -> tokens.revokeOwn(caller.userId(), tokenId));
        sessionLogin.end(request, response);
    }

    @GetMapping("/me")
    CurrentUserResponse me(TransportAuthentication caller) {
        return currentUser(caller.user());
    }

    private CurrentUserResponse currentUser(AuthenticatedUser user) {
        MembershipResponse membership =
                households.findCurrent(user.userId()).map(AuthController::membership).orElse(null);
        return new CurrentUserResponse(
                user.userId(), user.email(), user.displayName(), user.instanceAdmin(), membership);
    }

    private static MembershipResponse membership(CurrentHousehold household) {
        return new MembershipResponse(
                household.householdId(),
                household.name(),
                household.baseCurrency(),
                household.role(),
                household.membershipId(),
                household.displayCurrency(),
                household.locale());
    }

    /**
     * A browser's {@code User-Agent}, trimmed to something a person can recognise on their devices
     * screen. Truncated and stripped of control characters: it is attacker-controlled text that is
     * stored and later rendered, and a device label is not worth a log-injection or an XSS payload.
     */
    private static String browserLabel(HttpServletRequest request) {
        String agent = request.getHeader(HttpHeaders.USER_AGENT);
        if (agent == null || agent.isBlank()) {
            return UNLABELLED_BROWSER;
        }
        String cleaned = agent.replaceAll("[\\p{Cntrl}]", " ").strip();
        return cleaned.length() <= MAXIMUM_LABEL_LENGTH
                ? cleaned
                : cleaned.substring(0, MAXIMUM_LABEL_LENGTH);
    }
}
