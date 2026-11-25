package com.budgetowl.report.persistence;

import com.budgetowl.transaction.domain.Transaction;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The two report scans. <b>Read-only by construction</b>: there is no {@code save} and no {@code
 * delete} here, only grouped {@code select}s, and {@code ReportingArchitectureTest} holds that.
 *
 * <p><b>Every table in each query carries its own {@code householdId = :householdId}</b>: {@code
 * transactions}, {@code accounts} and {@code categories} (twice in the second query: the category
 * and its parent). That is not redundant with the composite foreign keys. The keys stop a row
 * <em>referencing</em> another household's row; they do nothing for an aggregate whose {@code
 * WHERE} forgot a table, which sums every household's rows legitimately. Each predicate is written
 * so that it alone keeps another household's rows out, and {@code ReportHouseholdScopingIT} proves
 * each one separately by lifting the database's own cross-household guards and seeding rows that
 * cross households (docs/features/reporting.md, attack 1).
 *
 * <p><b>Transfers are excluded on the base query</b>, once, with {@code t.transferGroupId is null}.
 * A transfer leg has no category ({@code ck_transactions_transfer_has_no_category}), so without the
 * predicate every leg in the household would land in the uncategorised bucket — the trap the
 * ledger's "Needs a category" queue already learned.
 *
 * <p><b>The date range is half-open, with the bare column on the left</b> ({@code t.date >= :from
 * and t.date < :until}) so {@code ix_transactions_household_date} can serve it. No {@code
 * date_trunc} and no {@code extract} in the {@code WHERE}; {@code extract} appears only in the
 * grouping, where it costs one evaluation per row already selected.
 *
 * <p><b>One statement per scan.</b> The signed splits are {@code sum(case …)} over a single pass,
 * so a report costs one query however many months or categories it has, and the query-count
 * assertions in the integration tests fail an N+1.
 *
 * <p>{@code PENDING} rows count, because an account's balance counts them ({@code
 * TransactionRepository.totalsByAccount} has no status filter) and the two must agree.
 */
public interface ReportRepository extends Repository<Transaction, UUID> {

    /**
     * Report 1: one row per (month, currency) that has at least one non-transfer transaction.
     *
     * <p>A categorised row is kept only when its category is this household's: {@code (t.categoryId
     * is null or c.householdId = :householdId)}. Written in the {@code WHERE} rather than the
     * join's {@code ON}, on purpose — in the {@code ON} it would turn a row filed under a foreign
     * category into an uncategorised one and count it, instead of leaving it out.
     *
     * @param from the first day of the first month, inclusive
     * @param until the first day of the month after the last, exclusive
     */
    @Query(
            """
            select new com.budgetowl.report.persistence.MonthTotals(
                       extract(year from t.date),
                       extract(month from t.date),
                       a.currency,
                       sum(case when c.kind = com.budgetowl.category.domain.CategoryKind.INCOME
                                then t.amount else 0 end),
                       sum(case when c.kind = com.budgetowl.category.domain.CategoryKind.EXPENSE
                                then t.amount else 0 end),
                       sum(case when t.categoryId is null and t.amount > 0
                                then t.amount else 0 end),
                       sum(case when t.categoryId is null and t.amount < 0
                                then t.amount else 0 end),
                       sum(t.amount),
                       sum(case when t.categoryId is null then 1 else 0 end),
                       count(t))
            from Transaction t
            join Account a on a.id = t.accountId
            left join Category c on c.id = t.categoryId
            where t.householdId = :householdId
              and a.householdId = :householdId
              and (t.categoryId is null or c.householdId = :householdId)
              and t.transferGroupId is null
              and t.date >= :from
              and t.date < :until
            group by extract(year from t.date), extract(month from t.date), a.currency
            """)
    List<MonthTotals> monthTotals(
            @Param("householdId") UUID householdId,
            @Param("from") LocalDate from,
            @Param("until") LocalDate until);

    /**
     * Report 2: one row per (currency, category) across the requested range and the range before
     * it, plus one row per currency for the uncategorised rows.
     *
     * <p>Every category kind comes back, income included. The service reads only expense rows and
     * the uncategorised outflow, but an income-only currency still has to appear in the report
     * (docs/features/reporting.md, Currencies), and filtering here would hide it.
     *
     * <p>The parent is joined for its name and archived flag, so the tree needs no second query. It
     * carries its own predicate: {@code fk_categories_parent} is not composite, and a trigger is
     * the only thing stopping a category being nested under another household's — whose name would
     * then be printed in this household's report.
     *
     * @param scanFrom the first day of the comparison range, inclusive
     * @param from the first day of the requested range: rows before it are the comparison
     * @param until the first day of the month after the requested range, exclusive
     */
    @Query(
            """
            select new com.budgetowl.report.persistence.CategoryTotals(
                       a.currency,
                       c.id, c.name, c.archived, c.kind,
                       p.id, p.name, p.archived,
                       sum(case when t.date >= :from then t.amount else 0 end),
                       sum(case when t.date < :from then t.amount else 0 end),
                       sum(case when t.date >= :from and t.amount < 0 then t.amount else 0 end),
                       sum(case when t.date >= :from and t.amount < 0 then 1 else 0 end))
            from Transaction t
            join Account a on a.id = t.accountId
            left join Category c on c.id = t.categoryId
            left join Category p on p.id = c.parentId
            where t.householdId = :householdId
              and a.householdId = :householdId
              and (t.categoryId is null or c.householdId = :householdId)
              and (c.parentId is null or p.householdId = :householdId)
              and t.transferGroupId is null
              and t.date >= :scanFrom
              and t.date < :until
            group by a.currency, c.id, c.name, c.archived, c.kind, p.id, p.name, p.archived
            """)
    List<CategoryTotals> categoryTotals(
            @Param("householdId") UUID householdId,
            @Param("scanFrom") LocalDate scanFrom,
            @Param("from") LocalDate from,
            @Param("until") LocalDate until);
}
