package com.budgetowl.auth.service;

import com.budgetowl.auth.domain.AuthenticatedUser;
import com.budgetowl.auth.domain.CredentialTransport;
import com.budgetowl.auth.domain.PasswordCredential;
import com.budgetowl.auth.domain.UserAccount;
import com.budgetowl.auth.persistence.PasswordCredentialRepository;
import com.budgetowl.auth.persistence.UserAccountRepository;
import com.budgetowl.common.AuthenticationFailedException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.ForbiddenException;
import com.budgetowl.instance.persistence.InstanceSettingsRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies an email and password, for both transports (ADR-0018).
 *
 * <p><b>An unknown email and a wrong password are the same event here.</b> Same exception, so the
 * same status and the same body; and a password comparison runs on both paths, against a dummy hash
 * when no user was found, so the two take the same time. The legacy app's distinct messages were a
 * user-enumeration oracle — anyone could POST a list of addresses and read off which ones had
 * accounts — and reproducing it is the single easiest way to undo this feature's value.
 *
 * <p>Three subtleties that look like over-care and are not:
 *
 * <ul>
 *   <li>A user who exists but has <em>no</em> password (provisioned through OIDC, never invited to
 *       set one) also gets the dummy comparison. Returning early there would time-leak exactly the
 *       fact the constant response is hiding.
 *   <li>A disabled account is refused with the same generic failure. "This account is disabled"
 *       confirms the address exists.
 *   <li>The rate limiter is keyed on the address that was <em>submitted</em>, not on one that was
 *       found, for the same reason.
 * </ul>
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private final UserAccountRepository users;
    private final PasswordCredentialRepository credentials;
    private final InstanceSettingsRepository settings;
    private final PasswordEncoder passwordEncoder;
    private final AuthRateLimiter rateLimiter;

    /** Stands in for a password too long to hash, so the comparison still happens. */
    private static final String OVERLONG_SUBSTITUTE = "not-a-password";

    /**
     * A real encoded password nobody knows, so the no-user path does the same work as the found
     * path. Computed once at startup from fresh randomness: a constant in the source would be a
     * published hash, and re-encoding per request would be a visible cost of its own.
     */
    private final String dummyHash;

    public AuthenticationService(
            UserAccountRepository users,
            PasswordCredentialRepository credentials,
            InstanceSettingsRepository settings,
            PasswordEncoder passwordEncoder,
            AuthRateLimiter rateLimiter) {
        this.users = users;
        this.credentials = credentials;
        this.settings = settings;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        // One UUID, not two: BCrypt refuses anything over 72 bytes, and two of them is 73.
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * @param clientIp the socket's remote address. Never a forwarded header unless the deployment
     *     is configured to trust one — an attacker sets {@code X-Forwarded-For} freely, and a rate
     *     limiter keyed on a value the attacker chooses is not a rate limiter.
     * @throws AuthenticationFailedException for every failure, whatever the reason
     */
    @Transactional(readOnly = true)
    public AuthenticatedUser authenticate(
            String email, String rawPassword, String clientIp, CredentialTransport transport) {
        List<String> keys = List.of(AuthRateLimiter.ip(clientIp), AuthRateLimiter.email(email));
        rateLimiter.requireAllowed(keys);
        requirePasswordLoginEnabled();

        String address = email.strip();
        Optional<PasswordCredential> credential = credentials.findByEmail(address);
        boolean matched = comparePassword(credential, rawPassword);

        Optional<UserAccount> user = matched ? users.findByEmail(address) : Optional.empty();
        if (!matched || user.isEmpty() || !user.get().isActive()) {
            rateLimiter.recordFailure(keys);
            log.info(
                    "authentication failed transport={} userId={} ip={}",
                    transport,
                    user.map(found -> found.id().toString()).orElse("unknown"),
                    clientIp);
            throw new AuthenticationFailedException("credentials refused");
        }

        rateLimiter.recordSuccess(keys);
        log.info(
                "authentication succeeded transport={} userId={} ip={}",
                transport,
                user.get().id(),
                clientIp);
        return AuthenticatedUser.of(user.get());
    }

    /**
     * Always runs exactly one password comparison, whatever it found.
     *
     * <p>The dummy branch is not a formality: without it, "no such user" returns in a millisecond
     * and "wrong password" returns in the hundreds, and the difference is measurable across the
     * internet.
     */
    private boolean comparePassword(Optional<PasswordCredential> credential, String rawPassword) {
        if (!PasswordPolicy.isEncodable(rawPassword)) {
            // Over BCrypt's 72-byte limit, which the encoder refuses by throwing. That has to be
            // an ordinary authentication failure rather than a 500: no stored password is longer
            // than the limit, so nothing this long can be right.
            passwordEncoder.matches(OVERLONG_SUBSTITUTE, dummyHash);
            return false;
        }
        if (credential.isPresent() && credential.get().isSet()) {
            return credential.get().matches(rawPassword, passwordEncoder::matches);
        }
        passwordEncoder.matches(rawPassword, dummyHash);
        return false;
    }

    /**
     * Password login can be hidden on an instance where OIDC is proven working, and only there —
     * the database refuses to disable it until an {@code OWNER} has completed an OIDC login ({@code
     * ck_instance_settings_password_login_lockout}).
     */
    private void requirePasswordLoginEnabled() {
        boolean enabled =
                settings.findCurrent()
                        .map(current -> current.isPasswordLoginEnabled())
                        .orElse(true);
        if (!enabled) {
            throw new ForbiddenException(
                    ErrorCode.FORBIDDEN, "password login is disabled on this instance");
        }
    }
}
