package com.budgetowl.checkin.persistence;

import com.budgetowl.checkin.domain.CheckIn;
import com.budgetowl.checkin.domain.CheckInStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Check-ins. Every method names a household: a week is not an identifier of another household's
 * data, it is resolved against the caller's.
 */
public interface CheckInRepository extends Repository<CheckIn, UUID> {

    CheckIn saveAndFlush(CheckIn checkIn);

    void delete(CheckIn checkIn);

    @Query(
            """
            select c from CheckIn c
            where c.householdId = :householdId and c.weekStart = :weekStart
            """)
    Optional<CheckIn> findForWeek(
            @Param("householdId") UUID householdId, @Param("weekStart") LocalDate weekStart);

    /**
     * The same row, locked {@code FOR UPDATE} until the transaction ends.
     *
     * <p>Every write to a draft takes this first — answering, discarding, completing — so a
     * completion cannot interleave with an answer, and two completions queue: the second reads the
     * row the first committed, finds it {@code COMPLETED}, and writes nothing (threat model, attack
     * 5).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select c from CheckIn c
            where c.householdId = :householdId and c.weekStart = :weekStart
            """)
    Optional<CheckIn> findForWeekForUpdate(
            @Param("householdId") UUID householdId, @Param("weekStart") LocalDate weekStart);

    /**
     * Locks the household row, so starting a check-in is serialised per household: two members
     * pressing Start at once get one draft and the same answer, rather than one draft and one
     * unique-index violation that aborts the other's transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Household h where h.id = :householdId")
    Optional<com.budgetowl.household.domain.Household> lockHousehold(
            @Param("householdId") UUID householdId);

    @Query(
            """
            select c from CheckIn c
            where c.householdId = :householdId and c.status = :status
            """)
    Optional<CheckIn> findInStatus(
            @Param("householdId") UUID householdId, @Param("status") CheckInStatus status);

    /**
     * Completed check-ins before a week, newest first. The first is the one that sets this week's
     * window and target; the rest are the streak, which the caller bounds with the page.
     */
    @Query(
            """
            select c from CheckIn c
            where c.householdId = :householdId
              and c.status = com.budgetowl.checkin.domain.CheckInStatus.COMPLETED
              and c.weekStart < :weekStart
            order by c.weekStart desc
            """)
    List<CheckIn> findCompletedBefore(
            @Param("householdId") UUID householdId,
            @Param("weekStart") LocalDate weekStart,
            Pageable pageable);

    @Query(
            value =
                    """
                    select c from CheckIn c
                    where c.householdId = :householdId
                    order by c.weekStart desc
                    """,
            countQuery =
                    """
                    select count(c) from CheckIn c where c.householdId = :householdId
                    """)
    Page<CheckIn> findHistory(@Param("householdId") UUID householdId, Pageable pageable);
}
