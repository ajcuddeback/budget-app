package com.budgetowl.household.persistence;

import com.budgetowl.household.domain.HouseholdInvitation;
import com.budgetowl.household.domain.HouseholdInvitationSummary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Invitations to join the household.
 *
 * <p>Owner-facing reads are scoped by household. Acceptance is not, and cannot be: the token is the
 * authorization and the person holding it has no membership yet, which is the entire point of an
 * invitation.
 */
public interface HouseholdInvitationRepository extends Repository<HouseholdInvitation, UUID> {

    HouseholdInvitation save(HouseholdInvitation invitation);

    /**
     * The acceptance path. Looked up by SHA-256 of the token, never by the token itself.
     *
     * <p>A miss, an expired invitation, a revoked one and an already-used one must all produce the
     * same generic failure at the edge — the caller must not turn this {@code Optional} into a
     * message that distinguishes them, or the link's history becomes a disclosure.
     */
    @EntityGraph(attributePaths = "household")
    Optional<HouseholdInvitation> findByTokenHash(String tokenHash);

    /** Scoped: revoking an invitation is an owner action on their own household's invitation. */
    Optional<HouseholdInvitation> findByIdAndHouseholdId(UUID id, UUID householdId);

    @Query(
            """
            select new com.budgetowl.household.domain.HouseholdInvitationSummary(
                i.id, i.email, i.role, i.expiresAt, i.acceptedAt, i.revokedAt, i.createdAt)
            from HouseholdInvitation i
            where i.household.id = :householdId
            order by i.createdAt desc
            """)
    List<HouseholdInvitationSummary> findSummariesByHouseholdId(
            @Param("householdId") UUID householdId);

    /**
     * Live invitations only — not accepted, not revoked, not expired.
     *
     * <p>{@code :now} is a parameter rather than {@code current_timestamp} so the clock is the
     * caller's injected one and a test does not have to wait for real time to pass.
     */
    @Query(
            """
            select new com.budgetowl.household.domain.HouseholdInvitationSummary(
                i.id, i.email, i.role, i.expiresAt, i.acceptedAt, i.revokedAt, i.createdAt)
            from HouseholdInvitation i
            where i.household.id = :householdId
              and i.acceptedAt is null
              and i.revokedAt is null
              and i.expiresAt > :now
            order by i.createdAt desc
            """)
    List<HouseholdInvitationSummary> findPendingSummariesByHouseholdId(
            @Param("householdId") UUID householdId, @Param("now") java.time.Instant now);

    /**
     * Whether this address already has a live invitation.
     *
     * <p>Native and cast: {@code household_invitations.email} is {@code citext}, and a JDBC {@code
     * varchar} parameter would compare case-sensitively. See {@code
     * UserAccountRepository.findByEmail}.
     */
    @Query(
            value =
                    """
                    SELECT EXISTS (
                        SELECT 1
                          FROM household_invitations
                         WHERE household_id = :householdId
                           AND email = CAST(:email AS citext)
                           AND accepted_at IS NULL
                           AND revoked_at IS NULL
                           AND expires_at > :now)
                    """,
            nativeQuery = true)
    boolean existsPendingForEmail(
            @Param("householdId") UUID householdId,
            @Param("email") String email,
            @Param("now") java.time.Instant now);
}
