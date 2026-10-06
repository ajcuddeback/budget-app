package com.budgetowl.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A login. "Account" in this product is a container where money sits, so a login is a <em>user</em>
 * (docs/memory/glossary.md).
 *
 * <p>A user may exist with no household membership — an OIDC-provisioned user before anyone has
 * invited them — and signs in successfully to an empty state rather than an error.
 *
 * <p><b>This class has no password field, deliberately.</b> {@code users.password_hash} is mapped
 * by {@link PasswordCredential} and nowhere else, so no amount of carelessness here can put a hash
 * into a response: there is nothing to put. That is the structural form of "hashes are never
 * selected into a DTO" asked for by docs/features/authentication-and-households.md.
 */
@Entity
@Table(name = "users")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * {@code citext}: addresses that differ only in case are the same address. Beware — a plain
     * {@code where email = :email} binds a JDBC {@code varchar} and compares case-SENSITIVELY.
     * Every lookup must cast; see {@code UserAccountRepository}.
     */
    @Column(name = "email", columnDefinition = "citext", nullable = false)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private UserStatus status;

    @Column(name = "is_instance_admin", nullable = false)
    private boolean instanceAdmin;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserAccount() {
        // for JPA
    }

    /**
     * Validation lives in the factories rather than here because SpotBugs' {@code
     * CT_CONSTRUCTOR_THROW} is right: a constructor that throws leaves a partially built object
     * reachable, and this class cannot be {@code final} — Hibernate needs to proxy it for the lazy
     * associations that point at it.
     */
    private static UserAccount create(String email, String displayName, boolean instanceAdmin) {
        UserAccount user = new UserAccount();
        user.email = Objects.requireNonNull(email, "email");
        user.displayName = Objects.requireNonNull(displayName, "displayName");
        user.status = UserStatus.ACTIVE;
        user.instanceAdmin = instanceAdmin;
        return user;
    }

    /** An ordinary user: no instance-administrator capability. */
    public static UserAccount member(String email, String displayName) {
        return create(email, displayName, false);
    }

    /**
     * The first user of a fresh instance, who is also its operator (ADR-0026). At most one such row
     * can exist — {@code uq_users_single_instance_admin} sees to that even if two callers of {@code
     * /api/setup/first-user} arrive at the same instant.
     */
    public static UserAccount instanceAdministrator(String email, String displayName) {
        return create(email, displayName, true);
    }

    public UUID id() {
        return id;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    public UserStatus status() {
        return status;
    }

    public boolean isInstanceAdministrator() {
        return instanceAdmin;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public void rename(String newDisplayName) {
        this.displayName = Objects.requireNonNull(newDisplayName, "displayName");
    }

    public void disable() {
        this.status = UserStatus.DISABLED;
    }

    public void enable() {
        this.status = UserStatus.ACTIVE;
    }

    @Override
    public boolean equals(Object other) {
        // Reflexive even before the row exists: an unsaved entity must still equal
        // itself, or putting one in a Set loses it.
        return this == other
                || (other instanceof UserAccount that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return UserAccount.class.hashCode();
    }

    /** No email here: this ends up in logs, and member addresses are PII and a target list. */
    @Override
    public String toString() {
        return "UserAccount[id=" + id + "]";
    }
}
