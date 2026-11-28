package com.budgetowl.income.persistence;

import com.budgetowl.income.domain.IncomeSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Income sources.
 *
 * <p>Every method takes a {@code householdId} or the entity itself — see {@code AccountRepository}
 * for the reasoning. Not paged, for the reason {@code BillRepository} gives.
 */
public interface IncomeSourceRepository extends Repository<IncomeSource, UUID> {

    IncomeSource save(IncomeSource source);

    /** See {@code AccountRepository.saveAndFlush} — {@code @Version} is bumped at flush. */
    IncomeSource saveAndFlush(IncomeSource source);

    void delete(IncomeSource source);

    Optional<IncomeSource> findByIdAndHouseholdId(UUID id, UUID householdId);

    @Query(
            """
            select s from IncomeSource s
            where s.householdId = :householdId
              and (:includeInactive = true or s.active = true)
            order by lower(s.name) asc, s.id asc
            """)
    List<IncomeSource> findAllForHousehold(
            @Param("householdId") UUID householdId,
            @Param("includeInactive") boolean includeInactive);

    long countByHouseholdId(UUID householdId);
}
