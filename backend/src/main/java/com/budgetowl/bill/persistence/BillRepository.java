package com.budgetowl.bill.persistence;

import com.budgetowl.bill.domain.Bill;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Bills.
 *
 * <p>Every method takes a {@code householdId} or the entity itself — see {@code AccountRepository}
 * for the reasoning and {@code ArchitectureTest} for what fails the build if one does not. Sorting
 * is fixed in the queries, never taken from a parameter.
 *
 * <p><b>Not paged.</b> A household has a handful of bills, the Bills &amp; Income page shows all of
 * them, and the page's headline figure is a sum over every one — a paged list would make that sum
 * either wrong or a second query. {@code BillService.MAXIMUM_PER_HOUSEHOLD} is the bound.
 */
public interface BillRepository extends Repository<Bill, UUID> {

    Bill save(Bill bill);

    /** See {@code AccountRepository.saveAndFlush} — {@code @Version} is bumped at flush. */
    Bill saveAndFlush(Bill bill);

    /** Deletes the bill and, by {@code fk_bill_occurrences_bill}, everything recorded about it. */
    void delete(Bill bill);

    Optional<Bill> findByIdAndHouseholdId(UUID id, UUID householdId);

    /**
     * One household's bills, retired ones included or not.
     *
     * <p>A retired bill is still listed when asked for: it holds the record of the periods it was
     * paid in, and hiding it would make last March unexplainable.
     */
    @Query(
            """
            select b from Bill b
            where b.householdId = :householdId
              and (:includeInactive = true or b.active = true)
            order by lower(b.name) asc, b.id asc
            """)
    List<Bill> findAllForHousehold(
            @Param("householdId") UUID householdId,
            @Param("includeInactive") boolean includeInactive);

    /**
     * The ids of the bills that the Bills &amp; Income page actually carries.
     *
     * <p>Exists for one rule in the period summary: a debt's minimum is left out of {@code
     * debtMinimumsNotInBills} when a bill already carries it, and a <b>retired</b> bill carries
     * nothing — {@code BillService.list} gives an inactive bill no occurrences, so it contributes
     * zero to the month's total. Without this query, retiring the bill a debt points at would leave
     * that minimum counted in neither place and the "what's left" headline would quietly rise by
     * it. Active-only is also exact rather than approximate: a bill recurs by day-of-month, so
     * every active bill has an occurrence in every period.
     */
    @Query("select b.id from Bill b where b.householdId = :householdId and b.active = true")
    Set<UUID> findActiveIdsForHousehold(@Param("householdId") UUID householdId);

    long countByHouseholdId(UUID householdId);
}
