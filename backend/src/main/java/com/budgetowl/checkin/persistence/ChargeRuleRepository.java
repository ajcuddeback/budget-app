package com.budgetowl.checkin.persistence;

import com.budgetowl.checkin.domain.ChargeRule;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Charge rules. Every method names a household. */
public interface ChargeRuleRepository extends Repository<ChargeRule, UUID> {

    ChargeRule saveAndFlush(ChargeRule rule);

    void delete(ChargeRule rule);

    Optional<ChargeRule> findByIdAndHouseholdId(UUID id, UUID householdId);

    long countByHouseholdId(UUID householdId);

    /**
     * Every rule. Bounded by {@code ChargeRuleService.MAXIMUM_PER_HOUSEHOLD}, so a check-in reads
     * them all in one query and indexes them by {@code (payee, account)}.
     */
    @Query(
            """
            select r from ChargeRule r
            where r.householdId = :householdId
            order by r.id asc
            """)
    List<ChargeRule> findAllForHousehold(@Param("householdId") UUID householdId);

    @Query(
            """
            select r from ChargeRule r
            where r.householdId = :householdId and r.billId in :billIds
            order by r.id asc
            """)
    List<ChargeRule> findForBills(
            @Param("householdId") UUID householdId, @Param("billIds") Collection<UUID> billIds);

    /** The list on Bills &amp; Income, by bill name and then id for a stable page. */
    @Query(
            value =
                    """
                    select r from ChargeRule r, Bill b
                    where r.householdId = :householdId
                      and b.householdId = :householdId
                      and b.id = r.billId
                    order by lower(b.name) asc, r.id asc
                    """,
            countQuery =
                    """
                    select count(r) from ChargeRule r where r.householdId = :householdId
                    """)
    Page<ChargeRule> findPage(@Param("householdId") UUID householdId, Pageable pageable);
}
