package com.budgetowl.goal.persistence;

import com.budgetowl.goal.domain.Goal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Goals.
 *
 * <p>Every method takes a {@code householdId} or the entity itself — see {@code AccountRepository}
 * for the reasoning and {@code ArchitectureTest} for what fails the build if one does not. The
 * order is fixed in the query, never taken from a parameter.
 */
public interface GoalRepository extends Repository<Goal, UUID> {

    /** See {@code AccountRepository.saveAndFlush} — {@code @Version} is bumped at flush. */
    Goal saveAndFlush(Goal goal);

    /**
     * Deletes a goal and, by the two {@code ON DELETE CASCADE} foreign keys, its plan and its
     * contributions. No transaction goes with it: a contribution only ever pointed at one.
     */
    void delete(Goal goal);

    Optional<Goal> findByIdAndHouseholdId(UUID id, UUID householdId);

    /** For the cap. Archived goals count: they still hold rows a list read walks. */
    long countByHouseholdId(UUID householdId);

    /**
     * One household's goals in the list's order: target month ascending with none last, then the
     * name case-insensitively, then the id so the order is total.
     */
    @Query(
            value =
                    """
                    select g from Goal g
                    where g.householdId = :householdId
                      and (:includeArchived = true or g.archived = false)
                    order by g.targetMonth asc nulls last, lower(g.name) asc, g.id asc
                    """,
            countQuery =
                    """
                    select count(g) from Goal g
                    where g.householdId = :householdId
                      and (:includeArchived = true or g.archived = false)
                    """)
    Page<Goal> findForHousehold(
            @Param("householdId") UUID householdId,
            @Param("includeArchived") boolean includeArchived,
            Pageable pageable);

    /**
     * Every unarchived goal in one currency — the goals a month's left-over figure is made of.
     *
     * <p>Bounded by the per-household cap, so it needs no page.
     */
    @Query(
            """
            select g from Goal g
            where g.householdId = :householdId
              and g.archived = false
              and g.currency = :currency
            order by g.id asc
            """)
    List<Goal> findUnarchivedInCurrency(
            @Param("householdId") UUID householdId, @Param("currency") String currency);
}
