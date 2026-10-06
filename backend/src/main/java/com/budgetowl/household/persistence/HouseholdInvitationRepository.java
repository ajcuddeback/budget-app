package com.budgetowl.household.persistence;

import com.budgetowl.household.domain.HouseholdInvitation;
import com.budgetowl.household.domain.HouseholdInvitationSummary;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Claims the invitation, once, for whoever gets there first.
     *
     * <p>Single-use cannot be built out of "read it, check it is unused, write it": between the
     * read and the write a second holder of the same link does the same, both see an unused
     * invitation and both join. One link, two memberships. That the collision is currently caught
     * by {@code uq_users_email} or {@code uq_household_members_household_id_user_id} is luck from
     * constraints that exist for other reasons — it stops being true the moment the joiner may
     * supply their own address, or an accept-while-signed-in path is added.
     *
     * <p>So this is the same shape as {@code InstanceSettingsRepository.claimFirstUserSetup}: a
     * conditional UPDATE of one row. The second caller blocks on the row lock, re-evaluates {@code
     * accepted_at is null} against the committed tuple, matches nothing and gets 0.
     *
     * <p>The caller must treat 0 exactly as it treats a token that was never issued — expired,
     * revoked, used and unknown are one response (threat model), and a new distinguishable outcome
     * here would tell whoever found a link in a chat backup what became of it.
     *
     * <p>{@code clearAutomatically}: a bulk update bypasses the persistence context, so any {@link
     * HouseholdInvitation} already loaded in this transaction would keep a snapshot saying {@code
     * acceptedAt = null} and a later dirty-check flush would write that back, un-spending the
     * invitation.
     *
     * @return 1 for the caller that claimed it, 0 for everyone else — including a caller whose
     *     invitation was already accepted, already revoked, or never existed
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update HouseholdInvitation i
               set i.acceptedAt = :when
             where i.id = :id
               and i.acceptedAt is null
               and i.revokedAt is null
            """)
    int markAccepted(@Param("id") UUID id, @Param("when") Instant when);

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
            @Param("householdId") UUID householdId, @Param("now") Instant now);

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
            @Param("now") Instant now);
}
