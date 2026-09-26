package com.budgetowl.auth.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * Who is making this request. The acting user is read from the {@code SecurityContext} and never
 * from a request body, query parameter or path variable (docs/architecture/security-model.md).
 *
 * <p>Deliberately small: an id, an address and whether this is the instance operator. It carries no
 * household and no role, because those are resolved from a <em>verified membership</em> at the
 * moment they are needed, not cached in a credential that would keep saying {@code OWNER} after the
 * role changed.
 *
 * <p>{@link Serializable} because the web transport stores it in the session table.
 *
 * @param userId the subject of every authorization decision that follows
 * @param displayName a display detail, read at sign-in. Never an input to an authorization decision
 *     — those all start from {@code userId} and a freshly read membership.
 */
public record AuthenticatedUser(
        UUID userId, String email, String displayName, boolean instanceAdmin)
        implements AuthenticatedPrincipal, Serializable {

    private static final long serialVersionUID = 1L;

    public AuthenticatedUser {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(displayName, "displayName");
    }

    public static AuthenticatedUser of(UserAccount user) {
        return new AuthenticatedUser(
                user.id(), user.email(), user.displayName(), user.isInstanceAdministrator());
    }

    /**
     * The principal name Spring Session indexes sessions by, which is how "this user's devices" and
     * "revoke everything this user has" are answerable at all. The id, not the address: an index
     * that holds an email address is a member directory in a table anybody restoring a backup can
     * read.
     */
    @Override
    public String getName() {
        return userId.toString();
    }

    /** No email: this ends up in logs, and member addresses are PII and a target list. */
    @Override
    public String toString() {
        return "AuthenticatedUser[userId=" + userId + "]";
    }
}
