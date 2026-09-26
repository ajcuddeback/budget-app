package com.budgetowl.auth.service;

import com.budgetowl.auth.domain.AuthenticatedUser;
import com.budgetowl.auth.domain.CredentialTransport;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The {@code Authentication} this application puts in the {@code SecurityContext}, whichever
 * transport the caller used (ADR-0018).
 *
 * <p>It carries <b>no authorities</b>, and that is the design. Roles are a property of a membership
 * in the one household, they change while a session is open, and a role baked into a credential at
 * login is a role that keeps saying {@code OWNER} after the owner was demoted. Every role decision
 * is made in the service layer against a freshly read membership — never here, and never in a
 * controller annotation.
 *
 * <p>{@link #getCredentials()} is always {@code null}: the bearer token that authenticated this
 * request is not kept, so nothing downstream can log it, echo it, or forward it.
 */
public final class TransportAuthentication
        extends org.springframework.security.authentication.AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final AuthenticatedUser user;
    private final CredentialTransport transport;
    private final UUID tokenId;

    private TransportAuthentication(
            AuthenticatedUser user, CredentialTransport transport, UUID tokenId) {
        super(AuthorityUtils.NO_AUTHORITIES);
        this.user = Objects.requireNonNull(user, "user");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.tokenId = tokenId;
        setAuthenticated(true);
    }

    public static TransportAuthentication session(AuthenticatedUser user) {
        return new TransportAuthentication(user, CredentialTransport.SESSION, null);
    }

    public static TransportAuthentication bearer(AuthenticatedUser user, UUID tokenId) {
        return new TransportAuthentication(
                user, CredentialTransport.BEARER, Objects.requireNonNull(tokenId, "tokenId"));
    }

    /** The current caller, when there is one. Empty for an anonymous or unauthenticated request. */
    public static Optional<TransportAuthentication> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof TransportAuthentication transport
                ? Optional.of(transport)
                : Optional.empty();
    }

    public AuthenticatedUser user() {
        return user;
    }

    public UUID userId() {
        return user.userId();
    }

    public CredentialTransport transport() {
        return transport;
    }

    /** The bearer token's row id, so logout can revoke exactly the device that called. */
    public Optional<UUID> tokenId() {
        return Optional.ofNullable(tokenId);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return user;
    }

    @Override
    public List<org.springframework.security.core.GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String toString() {
        return "TransportAuthentication[userId=" + user.userId() + ", transport=" + transport + "]";
    }
}
