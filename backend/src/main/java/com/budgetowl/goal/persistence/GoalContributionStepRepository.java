package com.budgetowl.goal.persistence;

import com.budgetowl.goal.domain.GoalContributionStep;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The steps of goals' monthly plans.
 *
 * <p>Every method names a household. The plan is bounded by {@code
 * uq_goal_contribution_steps_goal_from_period} and the service's refusal of past months, so it
 * grows by at most one row a month.
 */
public interface GoalContributionStepRepository extends Repository<GoalContributionStep, UUID> {

    GoalContributionStep saveAndFlush(GoalContributionStep step);

    /** One goal's plan, oldest first, for the plan endpoint. */
    @Query(
            value =
                    """
                    select s from GoalContributionStep s
                    where s.householdId = :householdId
                      and s.goalId = :goalId
                    order by s.fromPeriod asc
                    """,
            countQuery =
                    """
                    select count(s) from GoalContributionStep s
                    where s.householdId = :householdId
                      and s.goalId = :goalId
                    """)
    Page<GoalContributionStep> findPlan(
            @Param("householdId") UUID householdId,
            @Param("goalId") UUID goalId,
            Pageable pageable);

    /** Every step of these goals, oldest first: what a projection walks. One query for a list. */
    @Query(
            """
            select s from GoalContributionStep s
            where s.householdId = :householdId
              and s.goalId in :goalIds
            order by s.goalId asc, s.fromPeriod asc
            """)
    List<GoalContributionStep> findForGoals(
            @Param("householdId") UUID householdId, @Param("goalIds") Collection<UUID> goalIds);

    /**
     * Removes the steps from this month onwards: the first half of "from September, $165".
     *
     * <p>Bulk, and flushed first. The persistence context is deliberately <b>not</b> cleared: that
     * would detach the goal and the caller's membership mid-transaction. The steps it still holds
     * are never dirty, so they are never written back, and the new step is a new row.
     */
    @Modifying(flushAutomatically = true)
    @Query(
            """
            delete from GoalContributionStep s
            where s.householdId = :householdId
              and s.goalId = :goalId
              and s.fromPeriod >= :fromPeriod
            """)
    int deleteFrom(
            @Param("householdId") UUID householdId,
            @Param("goalId") UUID goalId,
            @Param("fromPeriod") LocalDate fromPeriod);
}
