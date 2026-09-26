package com.budgetowl.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * An opaque bearer token for the mobile transport (ADR-0018).
 *
 * <p>Opaque and server-side rather than self-contained, so revocation is immediate: a stolen phone
 * stops working on its very next request instead of when a signature expires.
 *
 * <p><b>The token itself is never stored and never readable.</b> Only its SHA-256 lives here, it is
 * write-only from Java — there is no accessor — and lookup is {@code
 * AuthTokenRepository.findByTokenHash}, an index probe rather than a byte-by-byte comparison of a
 * secret.
 */
@Entity
@Table(name = "auth_tokens")
public class AuthToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private UserAccount user;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "device_label", nullable = false)
    private String deviceLabel;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AuthToken() {
        // for JPA
    }

    /**
     * @param tokenHash lowercase-hex SHA-256 of the token. A fast hash is the right one for a value
     *     that is already 256 bits of randomness; the database enforces the shape ({@code
     *     ck_auth_tokens_token_hash_sha256}) so a plaintext token cannot be stored.
     */
    public static AuthToken issue(
            UserAccount user, String tokenHash, String deviceLabel, Instant expiresAt) {
        AuthToken token = new AuthToken();
        token.user = Objects.requireNonNull(user, "user");
        token.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        token.deviceLabel = Objects.requireNonNull(deviceLabel, "deviceLabel");
        token.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        return token;
    }

    public UUID id() {
        return id;
    }

    public UserAccount user() {
        return user;
    }

    public String deviceLabel() {
        return deviceLabel;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastUsedAt() {
        return lastUsedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public boolean isLiveAt(Instant when) {
        return revokedAt == null && expiresAt.isAfter(when);
    }

    public void markUsedAt(Instant when) {
        this.lastUsedAt = Objects.requireNonNull(when, "when");
    }

    /** Idempotent: revoking an already-revoked token keeps the original instant. */
    public void revokeAt(Instant when) {
        if (revokedAt == null) {
            this.revokedAt = Objects.requireNonNull(when, "when");
        }
    }

    /** Extends a sliding expiry. The caller owns the absolute cap. */
    public void extendTo(Instant newExpiry) {
        this.expiresAt = Objects.requireNonNull(newExpiry, "newExpiry");
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AuthToken that && id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return AuthToken.class.hashCode();
    }

    /** No hash and no device label: this is exactly the string that ends up in a log. */
    @Override
    public String toString() {
        return "AuthToken[id=" + id + "]";
    }
}
