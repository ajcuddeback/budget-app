package com.budgetowl.household.persistence;

import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.domain.HouseholdMemberSummary;
import com.budgetowl.household.domain.HouseholdRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Household membership — the row every authorization decision in every later slice rests on.
 *
 * <p><b>There is no {@code findById}.</b> A membership is household-owned data, so the only way to
 * reach one is with the household it belongs to ({@link #findByIdAndHouseholdId}), which keeps the
 * ADR-0008 scoping predicate present in the query shape even though ADR-0026 leaves exactly one
 * household to scope to. Keeping the shape is the point: it is what makes the dangerous call
 * unwritable if a managed deployment ever needs more than one.
 */
public interface HouseholdMemberRepository extends Repository<HouseholdMember, UUID> {

    HouseholdMember save(HouseholdMember member);

    /**
     * Removes a membership. Revokes access only — it never deletes household financial data, which
     * belongs to the household rather than to the person (ADR-0017).
     *
     * <p>Removing the last {@code OWNER} fails at {@code COMMIT}, from the database.
     */
    void delete(HouseholdMember member);

    /** Scoped. The unscoped version would be the bug this repository exists to prevent. */
    Optional<HouseholdMember> findByIdAndHouseholdId(UUID id, UUID householdId);

    /**
     * Resolves the caller's verified membership — the query behind every authorization decision.
     *
     * <p>An empty result is the "signed-in user with no membership" case (an OIDC-provisioned user
     * before anyone invited them). That is a {@code 403}, not a crash and not an empty success.
     */
    Optional<HouseholdMember> findByHouseholdIdAndUserId(UUID householdId, UUID userId);

    /** The same lookup when the household is resolved later; there is only one (ADR-0026). */
    @EntityGraph(attributePaths = {"household", "user"})
    Optional<HouseholdMember> findByUserId(UUID userId);

    /**
     * The members list, in one query.
     *
     * <p>An explicit join to the user, projected straight into a record: returning entities and
     * reading {@code member.user().displayName()} per row would be an N+1 that no unit test would
     * ever catch. {@code HouseholdMemberRepositoryIT} asserts the query count.
     *
     * <p>The sort is fixed here rather than taken from a parameter — an interpolated {@code ORDER
     * BY} is SQL injection with extra steps.
     */
    @Query(
            """
            select new com.budgetowl.household.domain.HouseholdMemberSummary(
                m.id, m.household.id, u.id, u.email, u.displayName,
                m.role, m.joinedAt, m.displayCurrency, m.locale)
            from HouseholdMember m
            join m.user u
            where m.household.id = :householdId
            order by m.joinedAt asc, u.email asc
            """)
    List<HouseholdMemberSummary> findSummariesByHouseholdId(@Param("householdId") UUID householdId);

    /**
     * Members with their users, for the rare caller that needs the entities.
     *
     * <p>{@code @EntityGraph} rather than a lazy proxy per row, for the same N+1 reason.
     */
    @EntityGraph(attributePaths = "user")
    List<HouseholdMember> findAllByHouseholdId(UUID householdId);

    boolean existsByHouseholdIdAndUserId(UUID householdId, UUID userId);

    /** See {@code UserAccountRepository.findByEmail} on why the cast is not optional. */
    @Query(
            value =
                    """
                    SELECT EXISTS (
                        SELECT 1
                          FROM household_members m
                          JOIN users u ON u.id = m.user_id
                         WHERE m.household_id = :householdId
                           AND u.email = CAST(:email AS citext))
                    """,
            nativeQuery = true)
    boolean existsByHouseholdIdAndUserEmail(
            @Param("householdId") UUID householdId, @Param("email") String email);

    /**
     * How many owners the household has.
     *
     * <p>For a friendly message before the attempt, never as the check itself: between this count
     * and the delete, another transaction can remove the other owner. The rule is the database's
     * (see {@code V3__households.sql}).
     */
    long countByHouseholdIdAndRole(UUID householdId, HouseholdRole role);

    long countByHouseholdId(UUID householdId);
}
