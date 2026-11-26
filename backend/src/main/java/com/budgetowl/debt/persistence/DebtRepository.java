package com.budgetowl.debt.persistence;

import com.budgetowl.debt.domain.Debt;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Debts.
 *
 * <p><b>Every method takes a {@code householdId} or the entity itself</b> — see {@code
 * AccountRepository} for the reasoning and {@code
 * ArchitectureTest.household_owned_data_is_unreachable_without_its_household} for what fails the
 * build if one does not. There is no {@code findById}: for a financial entity that method is the
 * bug ADR-0008 exists to prevent.
 *
 * <p><b>Not paged</b>, for the reason {@code BillRepository} gives: the debt plan ranks every debt
 * and its headline figures are sums over all of them, so a paged list would make the order and the
 * totals either wrong or a second query. {@code DebtService.MAXIMUM_PER_HOUSEHOLD} is the bound,
 * and it is a threat-model control rather than a tidiness rule — the plan projects both strategies
 * over every debt on one cheap request.
 *
 * <p>Sorting is fixed in the query, never taken from a parameter, and ends at the id so two debts
 * with the same name still come back in the same order on every read.
 */
public interface DebtRepository extends Repository<Debt, UUID> {

    Debt save(Debt debt);

    /** See {@code AccountRepository.saveAndFlush} — {@code @Version} is bumped at flush. */
    Debt saveAndFlush(Debt debt);

    /** Deletes the debt and, by {@code fk_debt_payments_debt}, its own payment history. */
    void delete(Debt debt);

    Optional<Debt> findByIdAndHouseholdId(UUID id, UUID householdId);

    /**
     * The same row, locked {@code FOR UPDATE} until the transaction ends.
     *
     * <p>For a payment write whose split depends on the other payments in its month (ADR-0040).
     * Logging reads "does this month already carry interest?" and then inserts. Without the lock,
     * two members logging at the same moment both read "no", and the month is charged twice.
     * Logging and deleting a payment both take it first, so whichever commits second sees the
     * first. It also makes the per-debt payment cap hold under concurrency.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select d from Debt d
            where d.id = :id and d.householdId = :householdId
            """)
    Optional<Debt> findByIdAndHouseholdIdForUpdate(
            @Param("id") UUID id, @Param("householdId") UUID householdId);

    /**
     * One household's debts, archived ones included or not.
     *
     * <p>An archived debt is still listed when asked for: it holds the history of what was paid
     * against it, and hiding it would make last year unexplainable. It takes no part in the plan.
     */
    @Query(
            """
            select d from Debt d
            where d.householdId = :householdId
              and (:includeArchived = true or d.archived = false)
            order by lower(d.name) asc, d.id asc
            """)
    List<Debt> findAllForHousehold(
            @Param("householdId") UUID householdId,
            @Param("includeArchived") boolean includeArchived);

    /**
     * The count the per-household cap consults before every insert.
     *
     * <p>Archived and paid-off debts count. The cap bounds the rows a household can create, and a
     * caller that could archive its way past it would have the amplification the cap exists to stop
     * (docs/features/debt-plan.md, Security considerations).
     */
    long countByHouseholdId(UUID householdId);
}
