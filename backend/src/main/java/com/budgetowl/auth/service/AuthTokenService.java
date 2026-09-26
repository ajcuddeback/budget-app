package com.budgetowl.auth.service;

import com.budgetowl.auth.domain.AuthToken;
import com.budgetowl.auth.domain.AuthenticatedUser;
import com.budgetowl.auth.domain.CredentialTransport;
import com.budgetowl.auth.persistence.AuthTokenRepository;
import com.budgetowl.auth.persistence.UserAccountRepository;
import com.budgetowl.common.OpaqueToken;
import com.budgetowl.config.SecurityProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque bearer tokens for the mobile transport (ADR-0018).
 *
 * <p>Opaque and server-side rather than a JWT, so revoking one is a {@code DELETE} that takes
 * effect on the device's very next request. A self-contained token stays valid until it expires
 * however loudly its owner asks for it to stop, which is the wrong answer for a stolen phone.
 *
 * <p>The token never exists in the database and is not recoverable from it: only a lowercase-hex
 * SHA-256 is stored, and authentication is an index probe on that hash rather than a comparison of
 * a secret — which is why the threat model rules out timing attacks here.
 */
@Service
public class AuthTokenService {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenService.class);

    private final AuthTokenRepository tokens;
    private final UserAccountRepository users;
    private final SecurityProperties.Token config;
    private final Clock clock;

    public AuthTokenService(
            AuthTokenRepository tokens,
            UserAccountRepository users,
            SecurityProperties properties,
            Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.config = properties.token();
        this.clock = clock;
    }

    /**
     * @param deviceLabel the user's own words for the device, shown on their devices screen
     */
    @Transactional
    public IssuedToken issue(UUID userId, String deviceLabel, String clientIp) {
        OpaqueToken token = OpaqueToken.generate();
        Instant now = clock.instant();
        AuthToken issued =
                tokens.save(
                        AuthToken.issue(
                                users.findById(userId).orElseThrow(),
                                token.sha256Hex(),
                                deviceLabel.strip(),
                                now.plus(config.idleTimeout())));
        log.info("bearer token issued userId={} tokenId={} ip={}", userId, issued.id(), clientIp);
        return new IssuedToken(
                issued.id(), token.value(), issued.deviceLabel(), issued.expiresAt());
    }

    /**
     * Authenticates a presented token and slides its expiry forward.
     *
     * <p>Revocation and expiry are read from the row on every request rather than trusted from the
     * credential, which is the whole reason the token is opaque: a revoked token and a logged-out
     * session are both rejected on the very next request.
     *
     * @return empty for an unknown, revoked, expired or disabled-owner token — the caller must not
     *     tell those apart in its response
     */
    @Transactional
    public Optional<TransportAuthentication> authenticate(String presentedToken) {
        Instant now = clock.instant();
        return tokens.findByTokenHash(OpaqueToken.of(presentedToken).sha256Hex())
                .filter(token -> token.isLiveAt(now))
                .filter(token -> token.user().isActive())
                .map(
                        token -> {
                            token.markUsedAt(now);
                            token.extendTo(slidingExpiry(token, now));
                            return TransportAuthentication.bearer(
                                    AuthenticatedUser.of(token.user()), token.id());
                        });
    }

    /** Sliding, but never past the absolute cap measured from when the token was created. */
    private Instant slidingExpiry(AuthToken token, Instant now) {
        Instant sliding = now.plus(config.idleTimeout());
        Instant cap = token.createdAt().plus(config.absoluteTimeout());
        Instant next = sliding.isAfter(cap) ? cap : sliding;
        return next.isAfter(token.expiresAt()) ? next : token.expiresAt();
    }

    /**
     * Ends the calling device's own session. Idempotent: logging out twice succeeds, and so does
     * logging out with a token that has already been revoked — a logout that reports anything is a
     * logout that tells an attacker whether they still had something.
     */
    @Transactional
    public void revokeOwn(UUID userId, UUID tokenId) {
        tokens.findByIdAndUserId(tokenId, userId)
                .ifPresent(token -> token.revokeAt(clock.instant()));
        log.info(
                "bearer token revoked userId={} tokenId={} transport={}",
                userId,
                tokenId,
                CredentialTransport.BEARER);
    }

    /**
     * Revokes every live token of one user at once: used when a member is removed from the
     * household and when they change their password. It has to be immediate — "a removed member
     * keeps using a mobile token" is only mitigated if the very next request fails.
     */
    @Transactional
    public int revokeAllFor(UUID userId) {
        int revoked = tokens.revokeAllForUser(userId, clock.instant());
        if (revoked > 0) {
            log.info("all bearer tokens revoked userId={} count={}", userId, revoked);
        }
        return revoked;
    }
}
