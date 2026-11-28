package com.budgetowl.checkin.persistence;

import com.budgetowl.checkin.domain.CheckInAnswer;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** A check-in's answers. Every method names the household and the check-in. */
public interface CheckInAnswerRepository extends Repository<CheckInAnswer, UUID> {

    CheckInAnswer saveAndFlush(CheckInAnswer answer);

    void delete(CheckInAnswer answer);

    @Query(
            """
            select a from CheckInAnswer a
            where a.householdId = :householdId and a.checkInId = :checkInId
            order by a.createdAt asc, a.id asc
            """)
    List<CheckInAnswer> findForCheckIn(
            @Param("householdId") UUID householdId, @Param("checkInId") UUID checkInId);

    /** The step 4 answers of a page of check-ins, in one query rather than one per row. */
    @Query(
            """
            select a from CheckInAnswer a
            where a.householdId = :householdId
              and a.checkInId in :checkInIds
              and a.itemKind = com.budgetowl.checkin.domain.ItemKind.ALLOCATION
            """)
    List<CheckInAnswer> findAllocations(
            @Param("householdId") UUID householdId,
            @Param("checkInIds") java.util.Collection<UUID> checkInIds);

    @Query(
            """
            select count(a) from CheckInAnswer a
            where a.householdId = :householdId and a.checkInId = :checkInId
            """)
    long countForCheckIn(
            @Param("householdId") UUID householdId, @Param("checkInId") UUID checkInId);
}
