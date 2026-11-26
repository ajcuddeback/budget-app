package com.budgetowl.checkin.persistence;

import com.budgetowl.transaction.domain.Transaction;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * What a check-in reads from the ledger. <b>Read-only by construction</b>: there is no {@code save}
 * and no {@code delete}, only {@code select}s, and {@code CheckInArchitectureTest} holds that. The
 * one write a check-in makes to a transaction, its category, goes through {@code
 * TransactionService.recategorise} and nowhere else.
 *
 * <p><b>Every table in each query carries its own {@code householdId = :householdId}</b>, the
 * reporting precedent: the composite foreign keys stop a row <em>referencing</em> another
 * household's, and do nothing for a {@code WHERE} that forgot a table.
 *
 * <p><b>Every list is bounded</b> by a {@link Pageable} the service sizes, and every scan by a date
 * range the service bounds (a window of at most 28 days, and twelve months of payee history for at
 * most the payees in it). No loop over these results is worse than linear: the service indexes them
 * by key (the slice-12 lesson about an O(n·m) match).
 *
 * <p>Transfers are excluded everywhere with {@code t.transferGroupId is null}: a transfer is never
 * an item and never spending.
 */
public interface CheckInLedgerRepository extends Repository<Transaction, UUID> {

    /** The four attachments, as one predicate. A row any of them names is already accounted for. */
    String ATTACHED =
            """
            (exists (select 1 from BillOccurrence o
                     where o.householdId = :householdId and o.transactionId = t.id)
             or exists (select 1 from DebtPayment d
                        where d.householdId = :householdId and d.transactionId = t.id)
             or exists (select 1 from GoalContribution g
                        where g.householdId = :householdId and g.transactionId = t.id)
             or exists (select 1 from EnvelopeEntry e
                        where e.householdId = :householdId and e.transactionId = t.id))
            """;

    String ROW =
            """
            select new com.budgetowl.checkin.persistence.LedgerRow(
                       t.id, t.accountId, a.name, a.lastFour, a.currency, t.date, t.amount,
                       t.payeeId, p.name, t.categoryId,
                       case when t.transferGroupId is null then false else true end,
                       t.version,
                       case when
            """
                    + ATTACHED
                    + """
                       then true else false end)
            from Transaction t
            join Account a on a.id = t.accountId
            left join Payee p on p.id = t.payeeId
            where t.householdId = :householdId
              and a.householdId = :householdId
              and (t.payeeId is null or p.householdId = :householdId)
            """;

    /** Step 2's queue: the ledger's "Needs a category" predicate, bounded by date. */
    @Query(
            ROW
                    + """
                      and a.currency = :currency
                      and t.categoryId is null
                      and t.transferGroupId is null
                      and t.date >= :from and t.date <= :to
                    order by t.date desc, t.id desc
                    """)
    List<LedgerRow> uncategorised(
            @Param("householdId") UUID householdId,
            @Param("currency") String currency,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            Pageable pageable);

    @Query(
            """
            select count(t) from Transaction t
            join Account a on a.id = t.accountId
            where t.householdId = :householdId
              and a.householdId = :householdId
              and a.currency = :currency
              and t.categoryId is null
              and t.transferGroupId is null
              and t.date >= :from and t.date <= :to
            """)
    long countUncategorised(
            @Param("householdId") UUID householdId,
            @Param("currency") String currency,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * Steps 1 and 3: outflows with a payee and nothing attached. A row with no payee has no history
     * to compare and no rule to match, so it is never asked about here.
     */
    @Query(
            ROW
                    + """
                      and a.currency = :currency
                      and t.transferGroupId is null
                      and t.payeeId is not null
                      and t.amount < 0
                      and t.date >= :from and t.date <= :to
                      and not
                    """
                    + ATTACHED
                    + """
                    order by t.date desc, t.id desc
                    """)
    List<LedgerRow> unattachedOutflows(
            @Param("householdId") UUID householdId,
            @Param("currency") String currency,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            Pageable pageable);

    /** Step 1's income lines: the positive rows that landed. */
    @Query(
            ROW
                    + """
                      and a.currency = :currency
                      and t.transferGroupId is null
                      and t.amount > 0
                      and t.date >= :from and t.date <= :to
                    order by t.date asc, t.id asc
                    """)
    List<LedgerRow> inflows(
            @Param("householdId") UUID householdId,
            @Param("currency") String currency,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            Pageable pageable);

    /** Named rows, in any currency, for re-checking answers at completion. */
    @Query(ROW + " and t.id in :ids")
    List<LedgerRow> rows(
            @Param("householdId") UUID householdId, @Param("ids") Collection<UUID> ids);

    /**
     * Earlier outflows from these payees, newest first, for the unusual-charge median and the
     * recurring-charge months. Bounded twice: by the payees (those in the window) and by the page.
     */
    @Query(
            """
            select new com.budgetowl.checkin.persistence.HistoryRow(
                       t.id, t.payeeId, t.accountId, t.date, t.amount)
            from Transaction t
            join Account a on a.id = t.accountId
            where t.householdId = :householdId
              and a.householdId = :householdId
              and a.currency = :currency
              and t.transferGroupId is null
              and t.amount < 0
              and t.payeeId in :payeeIds
              and t.date >= :from and t.date <= :to
            order by t.date desc, t.id desc
            """)
    List<HistoryRow> payeeHistory(
            @Param("householdId") UUID householdId,
            @Param("currency") String currency,
            @Param("payeeIds") Collection<UUID> payeeIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            Pageable pageable);

    /**
     * Unattached, non-transfer, base-currency rows grouped by day and category, with outflows and
     * inflows summed apart. Which groups are unbudgeted spending is {@code UnbudgetedSpending}'s
     * decision, in Java, once.
     */
    @Query(
            """
            select new com.budgetowl.checkin.persistence.SpendingGroup(
                       t.date, t.categoryId,
                       sum(case when t.amount < 0 then t.amount else 0 end),
                       sum(case when t.amount > 0 then t.amount else 0 end))
            from Transaction t
            join Account a on a.id = t.accountId
            where t.householdId = :householdId
              and a.householdId = :householdId
              and a.currency = :currency
              and t.transferGroupId is null
              and t.date >= :from and t.date <= :to
              and not
            """
                    + ATTACHED
                    + """
                    group by t.date, t.categoryId
                    """)
    List<SpendingGroup> spendingGroups(
            @Param("householdId") UUID householdId,
            @Param("currency") String currency,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** How many rows in the window are in another currency, for the step's "left out" line. */
    @Query(
            """
            select count(t) from Transaction t
            join Account a on a.id = t.accountId
            where t.householdId = :householdId
              and a.householdId = :householdId
              and a.currency <> :currency
              and t.transferGroupId is null
              and t.date >= :from and t.date <= :to
            """)
    long countInOtherCurrencies(
            @Param("householdId") UUID householdId,
            @Param("currency") String currency,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query(
            """
            select new com.budgetowl.checkin.persistence.CategoryRow(
                       c.id, c.name, c.kind, c.parentId, c.archived)
            from Category c
            where c.householdId = :householdId
            order by lower(c.name) asc, c.id asc
            """)
    List<CategoryRow> categories(@Param("householdId") UUID householdId);

    @Query(
            """
            select new com.budgetowl.checkin.persistence.EnvelopeCategory(e.period, e.categoryId)
            from Envelope e
            where e.householdId = :householdId
              and e.archived = false
              and e.categoryId is not null
              and e.period in :periods
            """)
    List<EnvelopeCategory> envelopeCategories(
            @Param("householdId") UUID householdId,
            @Param("periods") Collection<LocalDate> periods);

    /** Names for a completed check-in's summary. Retired bills included: their records stand. */
    @Query(
            """
            select new com.budgetowl.checkin.persistence.IdName(b.id, b.name) from Bill b
            where b.householdId = :householdId and b.id in :ids
            """)
    List<IdName> billNames(
            @Param("householdId") UUID householdId, @Param("ids") Collection<UUID> ids);

    @Query(
            """
            select new com.budgetowl.checkin.persistence.IdName(g.id, g.name) from Goal g
            where g.householdId = :householdId and g.id in :ids
            """)
    List<IdName> goalNames(
            @Param("householdId") UUID householdId, @Param("ids") Collection<UUID> ids);

    @Query(
            """
            select new com.budgetowl.checkin.persistence.IdName(d.id, d.name) from Debt d
            where d.householdId = :householdId and d.id in :ids
            """)
    List<IdName> debtNames(
            @Param("householdId") UUID householdId, @Param("ids") Collection<UUID> ids);

    @Query(
            """
            select new com.budgetowl.checkin.persistence.IdName(p.id, p.name) from Payee p
            where p.householdId = :householdId and p.id in :ids
            """)
    List<IdName> payeeNames(
            @Param("householdId") UUID householdId, @Param("ids") Collection<UUID> ids);

    /**
     * Locks the rows a completion is about to act on, in id order, until it commits.
     *
     * <p>Taken before the picture is re-derived, so nothing can re-file, attach, pair or edit them
     * between the check and the write: filing into an envelope and pairing into a transfer take the
     * same lock (slice-12 re-audit). The entities are only ever read here; the check-in package
     * calls no mutator on them, which {@code CheckInArchitectureTest} holds.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select t from Transaction t
            where t.householdId = :householdId and t.id in :ids
            order by t.id asc
            """)
    List<Transaction> lock(
            @Param("householdId") UUID householdId, @Param("ids") Collection<UUID> ids);
}
