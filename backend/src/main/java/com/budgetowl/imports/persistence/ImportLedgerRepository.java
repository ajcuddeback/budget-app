package com.budgetowl.imports.persistence;

import com.budgetowl.transaction.domain.Transaction;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * What import needs to read from, and write to, the ledger.
 *
 * <p>A repository of its own rather than more methods on {@code TransactionRepository}, so the
 * queries import depends on are in one place and are reviewed with it. Every method takes the
 * {@code householdId}, or the entity — {@code ArchitectureTest} reads the query text to check the
 * household is actually filtered on, not merely accepted.
 */
public interface ImportLedgerRepository extends Repository<Transaction, UUID> {

    Transaction save(Transaction transaction);

    /** Flushes every pending insert too, so a unique-index race surfaces inside the service. */
    Transaction saveAndFlush(Transaction transaction);

    /** The rows already carrying one of these {@code external_id}s: tier-1 duplicates. */
    @Query(
            """
            select new com.budgetowl.imports.persistence.ImportedRow(t.id, t.externalId)
            from Transaction t
            where t.householdId = :householdId
              and t.externalId in :externalIds
            """)
    List<ImportedRow> findImported(
            @Param("householdId") UUID householdId,
            @Param("externalIds") Collection<String> externalIds);

    /**
     * Every row in one account between two dates, whatever its origin — chiefly ones typed by hand,
     * which have no {@code external_id} and can never be a tier-1 duplicate. Transfer legs are
     * included on purpose: a transfer typed through "Move money" often reappears on the statement.
     */
    @Query(
            """
            select new com.budgetowl.imports.persistence.LedgerRow(t.id, t.date, t.amount)
            from Transaction t
            where t.householdId = :householdId
              and t.accountId = :accountId
              and t.date >= :from
              and t.date <= :to
            order by t.date asc, t.createdAt asc, t.id asc
            """)
    List<LedgerRow> findInAccountBetween(
            @Param("householdId") UUID householdId,
            @Param("accountId") UUID accountId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** How many rows an undo would remove: the batch's, except transfer legs (decision 5). */
    @Query(
            """
            select count(t) from Transaction t
            where t.householdId = :householdId
              and t.importBatchId = :batchId
              and t.transferGroupId is null
            """)
    long countRemovable(@Param("householdId") UUID householdId, @Param("batchId") UUID batchId);

    /**
     * Undo: the batch's rows that are not transfer legs. A row since paired into a transfer stays,
     * because removing one leg would leave money that left one account and arrived nowhere.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            """
            delete from Transaction t
            where t.householdId = :householdId
              and t.importBatchId = :batchId
              and t.transferGroupId is null
            """)
    int deleteRemovable(@Param("householdId") UUID householdId, @Param("batchId") UUID batchId);

    /** The account's balance as the ledger has it: opening balance aside, the sum of its rows. */
    @Query(
            """
            select coalesce(sum(t.amount), 0) from Transaction t
            where t.householdId = :householdId
              and t.accountId = :accountId
            """)
    java.math.BigDecimal sumForAccount(
            @Param("householdId") UUID householdId, @Param("accountId") UUID accountId);
}
