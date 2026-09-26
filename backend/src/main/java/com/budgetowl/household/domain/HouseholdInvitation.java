package com.budgetowl.household.domain;

import com.budgetowl.auth.domain.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * An invitation to join the household.
 *
 * <p>There is no SMTP server on a self-hosted box, so the mechanism is a link the owner shares
 * however they like. <b>The link is a credential</b>: possession of it is the authorization, and
 * the invited email address is a label rather than a check. It is never logged and never recorded
 * in a URL we keep.
 *
 * <p><b>The token is never stored and is not readable from Java.</b> Only its SHA-256 lives here,
 * write-only, and acceptance looks the invitation up by that hash. Expired, revoked and
 * already-used must all produce an <em>identical</em> generic failure at the edge, so that a link's
 * history is not disclosed — this class answers the questions separately on purpose, and the
 * service is responsible for collapsing them into one response.
 */
@Entity
@Table(name = "household_invitations")
public class HouseholdInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false, updatable = false)
    private Household household;

    @Column(name = "email", columnDefinition = "citext", nullable = false, updatable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private HouseholdRole role;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private UserAccount createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected HouseholdInvitation() {
        // for JPA
    }

    /**
     * @param tokenHash lowercase-hex SHA-256 of the high-entropy token that went into the link. The
     *     database refuses any other shape ({@code ck_household_invitations_token_hash_sha256}), so
     *     a plaintext token cannot be stored even by mistake.
     */
    public static HouseholdInvitation create(
            Household household,
            String email,
            HouseholdRole role,
            String tokenHash,
            Instant expiresAt,
            UserAccount createdBy) {
        HouseholdInvitation invitation = new HouseholdInvitation();
        invitation.household = Objects.requireNonNull(household, "household");
        invitation.email = Objects.requireNonNull(email, "email");
        invitation.role = Objects.requireNonNull(role, "role");
        invitation.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        invitation.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        invitation.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        return invitation;
    }

    public UUID id() {
        return id;
    }

    public Household household() {
        return household;
    }

    public String email() {
        return email;
    }

    public HouseholdRole role() {
        return role;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant acceptedAt() {
        return acceptedAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public UserAccount createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public boolean isUsableAt(Instant when) {
        return acceptedAt == null && revokedAt == null && expiresAt.isAfter(when);
    }

    /**
     * Marks the invitation used. Single-use: a second acceptance must fail, and the caller keeps
     * this in the same transaction as the membership insert so the two cannot diverge.
     *
     * @throws IllegalStateException if it was already accepted or revoked
     */
    public void acceptAt(Instant when) {
        Objects.requireNonNull(when, "when");
        if (acceptedAt != null || revokedAt != null) {
            throw new IllegalStateException("invitation " + id + " is no longer usable");
        }
        this.acceptedAt = when;
    }

    /** Idempotent. Revoking an accepted invitation is refused — it is already spent. */
    public void revokeAt(Instant when) {
        Objects.requireNonNull(when, "when");
        if (acceptedAt != null) {
            throw new IllegalStateException("invitation " + id + " has already been accepted");
        }
        if (revokedAt == null) {
            this.revokedAt = when;
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HouseholdInvitation that && id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return HouseholdInvitation.class.hashCode();
    }

    /** No token hash and no email: invitations are credentials and addresses are PII. */
    @Override
    public String toString() {
        return "HouseholdInvitation[id=" + id + ", role=" + role + "]";
    }
}
