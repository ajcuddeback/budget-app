package com.budgetowl.checkin.persistence;

import com.budgetowl.checkin.domain.PayeeRule;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Payee rules. Every method names a household. */
public interface PayeeRuleRepository extends Repository<PayeeRule, UUID> {

    PayeeRule saveAndFlush(PayeeRule rule);

    void delete(PayeeRule rule);

    Optional<PayeeRule> findByIdAndHouseholdId(UUID id, UUID householdId);

    long countByHouseholdId(UUID householdId);

    @Query(
            """
            select r from PayeeRule r
            where r.householdId = :householdId and r.payeeId in :payeeIds
            """)
    List<PayeeRule> findForPayees(
            @Param("householdId") UUID householdId, @Param("payeeIds") Collection<UUID> payeeIds);

    /** The categories page's list, by payee name and then id for a stable page. */
    @Query(
            value =
                    """
                    select r from PayeeRule r, Payee p
                    where r.householdId = :householdId
                      and p.householdId = :householdId
                      and p.id = r.payeeId
                    order by lower(p.name) asc, r.id asc
                    """,
            countQuery =
                    """
                    select count(r) from PayeeRule r where r.householdId = :householdId
                    """)
    Page<PayeeRule> findPage(@Param("householdId") UUID householdId, Pageable pageable);
}
