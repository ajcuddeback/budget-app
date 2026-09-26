package com.budgetowl.auth.service;

import com.budgetowl.auth.domain.AuthTokenSummary;
import com.budgetowl.auth.domain.CredentialTransport;
import com.budgetowl.auth.persistence.AuthTokenRepository;
import com.budgetowl.common.NotFoundException;
import com.budgetowl.common.OpaqueToken;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Where am I signed in?", and the answer to a lost phone.
 *
 * <p>Both transports in one list (ADR-0018), each entry individually revocable, and revocation
 * immediate: sessions and tokens are both server-side, which is the reason bearer tokens are opaque
 * rather than self-contained.
 *
 * <p>Sessions are found through Spring Session's principal-name index, which holds the user's id
 * because {@code AuthenticatedUser.getName()} returns it — an index of email addresses would be a
 * member directory sitting in a table.
 */
@Service
public class DeviceService {

    /** The user's own words for the device, set at login; see {@code AuthController}. */
    public static final String LABEL_ATTRIBUTE = "budgetowl.deviceLabel";

    private static final String SESSION_PREFIX = "s-";
    private static final String TOKEN_PREFIX = "t-";
    private static final String UNLABELLED = "Web browser";

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final AuthTokenRepository tokens;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final Clock clock;

    public DeviceService(
            AuthTokenRepository tokens,
            FindByIndexNameSessionRepository<? extends Session> sessions,
            Clock clock) {
        this.tokens = tokens;
        this.sessions = sessions;
        this.clock = clock;
    }

    /**
     * @param currentSessionId the caller's own session id, so the list can mark it — hashed here
     *     and never returned
     */
    @Transactional(readOnly = true)
    public List<Device> devicesOf(UUID userId, String currentSessionId, UUID currentTokenId) {
        Instant now = clock.instant();
        List<Device> devices = new ArrayList<>();

        String currentSessionHandle = currentSessionId == null ? null : handleFor(currentSessionId);
        for (Session session : sessionsOf(userId)) {
            String handle = handleFor(session.getId());
            devices.add(
                    new Device(
                            handle,
                            CredentialTransport.SESSION,
                            labelOf(session),
                            session.getCreationTime(),
                            session.getLastAccessedTime(),
                            session.getLastAccessedTime().plus(session.getMaxInactiveInterval()),
                            handle.equals(currentSessionHandle)));
        }

        for (AuthTokenSummary token : tokens.findSummariesByUserId(userId)) {
            if (token.revokedAt() != null || token.expiresAt().isBefore(now)) {
                continue;
            }
            devices.add(
                    new Device(
                            TOKEN_PREFIX + token.id(),
                            CredentialTransport.BEARER,
                            token.deviceLabel(),
                            token.createdAt(),
                            token.lastUsedAt(),
                            token.expiresAt(),
                            token.id().equals(currentTokenId)));
        }

        devices.sort(Comparator.comparing(Device::createdAt).reversed());
        return List.copyOf(devices);
    }

    /**
     * Revokes one device of the calling user.
     *
     * <p>Scoped to {@code userId} on both branches, so a caller cannot revoke somebody else's
     * session by guessing a handle — and an unknown handle is a {@code 404} whether it never
     * existed or belongs to another user, which is the same rule as everywhere else (ADR-0008).
     */
    @Transactional
    public void revoke(UUID userId, String deviceId) {
        if (deviceId.startsWith(TOKEN_PREFIX)) {
            revokeToken(userId, deviceId.substring(TOKEN_PREFIX.length()));
            return;
        }
        if (deviceId.startsWith(SESSION_PREFIX)) {
            revokeSession(userId, deviceId);
            return;
        }
        throw new NotFoundException("no such device");
    }

    /**
     * Everything this user holds, in one go: used when they are removed from the household and when
     * they change their password. Removal has to invalidate access immediately, or a removed member
     * keeps reading the household's finances from a phone nobody can see.
     */
    @Transactional
    public void revokeEverythingFor(UUID userId) {
        tokens.revokeAllForUser(userId, clock.instant());
        for (Session session : sessionsOf(userId)) {
            sessions.deleteById(session.getId());
        }
        log.info("all credentials revoked userId={}", userId);
    }

    private void revokeToken(UUID userId, String rawId) {
        UUID tokenId = parseUuid(rawId);
        tokens.findByIdAndUserId(tokenId, userId)
                .orElseThrow(() -> new NotFoundException("no such device"))
                .revokeAt(clock.instant());
        log.info("device revoked userId={} tokenId={}", userId, tokenId);
    }

    private void revokeSession(UUID userId, String handle) {
        Session match =
                sessionsOf(userId).stream()
                        .filter(session -> handle.equals(handleFor(session.getId())))
                        .findFirst()
                        .orElseThrow(() -> new NotFoundException("no such device"));
        sessions.deleteById(match.getId());
        log.info("device revoked userId={} kind={}", userId, CredentialTransport.SESSION);
    }

    private List<? extends Session> sessionsOf(UUID userId) {
        Map<String, ? extends Session> found = sessions.findByPrincipalName(userId.toString());
        return List.copyOf(found.values());
    }

    private static String handleFor(String sessionId) {
        return SESSION_PREFIX + OpaqueToken.sha256Hex(sessionId);
    }

    private static String labelOf(Session session) {
        return Optional.ofNullable(session.<String>getAttribute(DeviceService.LABEL_ATTRIBUTE))
                .filter(label -> !label.isBlank())
                .orElse(UNLABELLED);
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException notAUuid) {
            throw new NotFoundException("no such device");
        }
    }
}
