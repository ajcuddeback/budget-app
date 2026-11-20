package com.budgetowl.transaction.persistence;

import com.budgetowl.transaction.domain.Transaction;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Transactions — the repository the rest of the product reads money through.
 *
 * <p><b>Every method takes a {@code householdId}</b>, or takes the entity itself. See {@code
 * AccountRepository} for the full reasoning; {@code
 * ArchitectureTest.household_owned_data_is_unreachable_without_its_household} fails the build if an
 * unscoped lookup is added, and inspects the query text as well as the signature.
 *
 * <p><b>Filters are nullable bound parameters, not assembled SQL.</b> The ledger takes an account,
 * a category, a date range and a free-text search; each one is applied by an {@code (:param is null
 * or ...)} clause so there is a single query with a single plan rather than a string built from
 * caller input. An {@code ORDER BY} is never taken from a parameter
 * (docs/guides/database-style.md).
 *
 * <p>Sorting is {@code date DESC, id DESC} throughout, matching {@code
 * ix_transactions_household_date}: the tiebreak on {@code id} is what stops paging repeating or
 * skipping a row when several transactions share a date, which for a ledger is the normal case.
 *
 * <p><b>Why "no filter" is a match-all VALUE for the text and date filters, and a null only for the
 * ids.</b> Hibernate renders every occurrence of a named parameter as its own placeholder, so
 * {@code (:from is null or t.date >= :from)} becomes {@code (? is null or date >= ?)} — two binds.
 * A bind that appears only inside {@code IS NULL} has no context to take a type from, and whether
 * that is fatal depends on the driver: pgjdbc sends a UUID with its type OID, so {@code ? is null}
 * resolves, but it sends a {@code LocalDate} unspecified, and PostgreSQL answers <em>could not
 * determine data type of parameter $6</em> and the ledger returns 500. A {@code like} pattern fails
 * the same way, and lowercasing in SQL fails even earlier with {@code lower(bytea) does not exist}.
 * An HQL {@code cast(:from as LocalDate)} is no help either: the Java type already matches, so
 * Hibernate emits no SQL cast to carry the type.
 *
 * <p>So the date bounds and the search pattern are always real values — {@code
 * LedgerFilter.EARLIEST} / {@code LATEST} and {@code "%"} — and mean "everything" by matching
 * everything. The id filters keep {@code (:id is null or ...)} because their binds are typed and
 * the null reads more clearly than a sentinel UUID, which does not exist. All of it was found by
 * the ledger tests, which is why they exercise each filter separately rather than only the
 * unfiltered list.
 */
public interface TransactionRepository extends Repository<Transaction, UUID> {

    Transaction save(Transaction transaction);

    /** See {@code AccountRepository.saveAndFlush} — {@code @Version} is bumped at flush. */
    Transaction saveAndFlush(Transaction transaction);

    /**
     * Hard-deletes a transaction.
     *
     * <p>A real delete rather than a tombstone: a household that mistypes an amount needs the wrong
     * figure gone, not struck through for ever. "Append-mostly" in {@code docs/domain/model.md}
     * describes corrections keeping an audit trail, which is what {@code created_at} / {@code
     * updated_at} and the version counter are for — not a graveyard of deleted rows that every
     * later sum has to remember to exclude.
     */
    void delete(Transaction transaction);

    Optional<Transaction> findByIdAndHouseholdId(UUID id, UUID householdId);

    /**
     * The ledger: one household's transactions, newest first, narrowed by whichever filters the
     * caller supplied.
     *
     * @param accountId null for every account
     * @param categoryId null for every category. A null cannot express "uncategorised only",
     *     because it already means "do not filter" — hence the separate flag below
     * @param uncategorisedOnly true for the work queue: transactions with no category, excluding
     *     transfers. It composes with every other filter here, so "what still needs a category on
     *     the current account" is one query and one answer
     * @param from the inclusive lower bound, and <b>never null</b> — "no lower bound" is {@code
     *     LedgerFilter.EARLIEST}. See the class note on why.
     * @param to the inclusive upper bound, never null — "no upper bound" is {@code
     *     LedgerFilter.LATEST}
     * @param search null for no text search; otherwise an already-lowercased, already-escaped
     *     {@code like} pattern. <b>The lowercasing happens in Java, not in SQL.</b> {@code
     *     lower(:search)} would read better, but this parameter is nullable and Hibernate cannot
     *     infer a type for a null bind — PostgreSQL then sees {@code lower(bytea)} and the whole
     *     ledger is a 500. Only the COLUMN is lowered here; {@code TransactionService.likePattern}
     *     lowers the pattern to match.
     */
    @Query(
            value =
                    """
                    select t from Transaction t
                    where t.householdId = :householdId
                      and (:accountId is null or t.accountId = :accountId)
                      and (:categoryId is null or t.categoryId = :categoryId)
                      and (:uncategorisedOnly = false
                           or (t.categoryId is null and t.transferGroupId is null))
                      and t.date >= :from
                      and t.date <= :to
                      and (lower(coalesce(t.note, '')) like :search escape '\\'
                           or t.payeeId in (
                               select p.id from Payee p
                               where p.householdId = :householdId
                                 and lower(p.name) like :search escape '\\'))
                    order by t.date desc, t.id desc
                    """,
            countQuery =
                    """
                    select count(t) from Transaction t
                    where t.householdId = :householdId
                      and (:accountId is null or t.accountId = :accountId)
                      and (:categoryId is null or t.categoryId = :categoryId)
                      and (:uncategorisedOnly = false
                           or (t.categoryId is null and t.transferGroupId is null))
                      and t.date >= :from
                      and t.date <= :to
                      and (lower(coalesce(t.note, '')) like :search escape '\\'
                           or t.payeeId in (
                               select p.id from Payee p
                               where p.householdId = :householdId
                                 and lower(p.name) like :search escape '\\'))
                    """)
    Page<Transaction> findLedger(
            @Param("householdId") UUID householdId,
            @Param("accountId") UUID accountId,
            @Param("categoryId") UUID categoryId,
            @Param("uncategorisedOnly") boolean uncategorisedOnly,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("search") String search,
            Pageable pageable);

    /**
     * The posted total per account, with each account's currency alongside it.
     *
     * <p>The currency comes from the join because a transaction does not carry one — it is
     * denominated by its account (see {@code V12}) — and the caller needs a {@code Money}, which
     * cannot exist without it. Grouping by the currency as well as the account is free: an account
     * has exactly one.
     *
     * <p><b>Batched by design.</b> The accounts list asks about every row at once; asking per
     * account would be an N+1 on the hot path of the main screen.
     */
    @Query(
            """
            select new com.budgetowl.transaction.persistence.AccountTotal(
                       t.accountId, a.currency, sum(t.amount))
            from Transaction t, Account a
            where a.id = t.accountId
              and a.householdId = :householdId
              and t.householdId = :householdId
              and t.accountId in :accountIds
            group by t.accountId, a.currency
            """)
    List<AccountTotal> totalsByAccount(
            @Param("householdId") UUID householdId,
            @Param("accountIds") Collection<UUID> accountIds);

    /**
     * Which of these accounts have any transaction at all.
     *
     * <p>Deliberately not derived from {@link #totalsByAccount}: a total of zero is not the same
     * fact as no transactions, and an account with +50 and -50 has history whose currency must not
     * be reinterpreted and whose rows must not be deleted.
     */
    @Query(
            """
            select distinct t.accountId from Transaction t
            where t.householdId = :householdId
              and t.accountId in :accountIds
            """)
    List<UUID> accountIdsWithHistory(
            @Param("householdId") UUID householdId,
            @Param("accountIds") Collection<UUID> accountIds);

    /**
     * Whether this one account has any transaction. Used by the delete and re-denominate guards.
     */
    @Query(
            """
            select count(t) > 0 from Transaction t
            where t.householdId = :householdId
              and t.accountId = :accountId
            """)
    boolean existsForAccount(
            @Param("householdId") UUID householdId, @Param("accountId") UUID accountId);

    /** For the empty state, which on first run is the common case rather than an error. */
    long countByHouseholdId(UUID householdId);
}
