package com.budgetowl.goal.persistence;

import com.budgetowl.goal.domain.GoalContribution;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Contributions to goals.
 *
 * <p>Every method names a household, and the ones that take a contribution id also name its goal:
 * an id from a path is a lookup key, and a contribution of the right household but the wrong goal
 * is still the wrong contribution for the request that asked for it (threat model, attack 4).
 */
public interface GoalContributionRepository extends Repository<GoalContribution, UUID> {

    GoalContribution saveAndFlush(GoalContribution contribution);

    void delete(GoalContribution contribution);

    @Query(
            """
            select c from GoalContribution c
            where c.id = :id
              and c.goalId = :goalId
              and c.householdId = :householdId
            """)
    Optional<GoalContribution> findInGoal(
            @Param("id") UUID id,
            @Param("goalId") UUID goalId,
            @Param("householdId") UUID householdId);

    /**
     * The history, newest first. Ties are broken by insertion time and then id: several rows
     * routinely share a date, and a random UUID alone is a coin flip (docs/memory/gotchas.md).
     */
    @Query(
            value =
                    """
                    select c from GoalContribution c
                    where c.householdId = :householdId
                      and c.goalId = :goalId
                    order by c.contributedOn desc, c.createdAt desc, c.id desc
                    """,
            countQuery =
                    """
                    select count(c) from GoalContribution c
                    where c.householdId = :householdId
                      and c.goalId = :goalId
                    """)
    Page<GoalContribution> findHistory(
            @Param("householdId") UUID householdId,
            @Param("goalId") UUID goalId,
            Pageable pageable);

    /** For the per-goal cap. */
    long countByHouseholdIdAndGoalId(UUID householdId, UUID goalId);

    /**
     * Each goal's contributions summed by the month they count towards.
     *
     * <p><b>Batched by design</b>, and the only shape the figures are read in. {@code saved}, this
     * month's recorded amount, and the left-over figure's {@code monthContribution} are all sums
     * over these rows; reading them from one grouped query means the list, the detail page and the
     * period summary all work from the same totals.
     */
    @Query(
            """
            select new com.budgetowl.goal.persistence.GoalPeriodTotal(
                       c.goalId, c.period, c.provenance, sum(c.amount))
            from GoalContribution c
            where c.householdId = :householdId
              and c.goalId in :goalIds
            group by c.goalId, c.period, c.provenance
            """)
    List<GoalPeriodTotal> totalsByPeriod(
            @Param("householdId") UUID householdId, @Param("goalIds") Collection<UUID> goalIds);

    /**
     * The contribution this transaction is already evidence for, if any.
     *
     * <p>{@code ux_goal_contributions_transaction_id} is the control; this is the good message in
     * front of it.
     */
    @Query(
            """
            select c from GoalContribution c
            where c.householdId = :householdId
              and c.transactionId = :transactionId
            """)
    Optional<GoalContribution> findByTransaction(
            @Param("householdId") UUID householdId, @Param("transactionId") UUID transactionId);
}
