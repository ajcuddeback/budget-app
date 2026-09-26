package com.budgetowl.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The encoded password of one user, and the only mapping of {@code users.password_hash} anywhere in
 * the codebase.
 *
 * <p><b>The hash goes in and a boolean comes out.</b> There is no getter, no record component and
 * no {@code toString} that can reach it, so it cannot be copied into a DTO, a log line, a problem
 * response or a debugger-friendly string by accident. That is what
 * docs/features/authentication-and-households.md means by "projections that cannot carry them
 * rather than relying on annotations to hide them" — an annotation is a thing somebody has to
 * remember, and this is a thing nobody can forget.
 *
 * <p>Mapped onto the {@code users} table alongside {@link UserAccount}, which deliberately has no
 * password field at all. The split is the point: the entity every service touches cannot leak what
 * it does not have.
 *
 * <p>The value is nullable. An OIDC-provisioned user has no password, and {@code NULL} says so
 * honestly where an empty string would not.
 */
@Entity
@Table(name = "users")
public class PasswordCredential {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "password_hash")
    private String passwordHash;

    protected PasswordCredential() {
        // for JPA
    }

    public UUID userId() {
        return userId;
    }

    /** Whether this user can authenticate with a password at all. */
    public boolean isSet() {
        return passwordHash != null;
    }

    /**
     * Verifies a submitted password.
     *
     * <p>Returns {@code false} when no password is set rather than throwing, so the caller's
     * unknown-user and wrong-password paths stay identical — but the caller must still run its
     * dummy-hash comparison, because returning early here is a timing oracle.
     */
    public boolean matches(CharSequence rawPassword, PasswordMatcher matcher) {
        Objects.requireNonNull(matcher, "matcher");
        if (passwordHash == null || rawPassword == null) {
            return false;
        }
        return matcher.matches(rawPassword, passwordHash);
    }

    /**
     * Replaces the encoded password.
     *
     * @param encodedPassword output of a {@code PasswordEncoder}, never a plaintext password. The
     *     database refuses anything that is not an encoded form ({@code
     *     ck_users_password_hash_encoded}), so a mistake here fails loudly rather than storing a
     *     credential in the clear.
     */
    public void replaceWith(String encodedPassword) {
        this.passwordHash = Objects.requireNonNull(encodedPassword, "encodedPassword");
    }

    /** Leaves the user unable to authenticate with a password, without deleting the user. */
    public void clear() {
        this.passwordHash = null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PasswordCredential that
                && userId != null
                && userId.equals(that.userId);
    }

    @Override
    public int hashCode() {
        return PasswordCredential.class.hashCode();
    }

    @Override
    public String toString() {
        return "PasswordCredential[userId=" + userId + ", set=" + isSet() + "]";
    }
}
