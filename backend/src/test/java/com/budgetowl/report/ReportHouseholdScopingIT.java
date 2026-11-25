package com.budgetowl.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.persistence.PersistenceTestBase;
import com.budgetowl.report.service.IncomeAndSpendingReport;
import com.budgetowl.report.service.ReportService;
import com.budgetowl.report.service.SpendingByCategoryReport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * <b>Another household's rows never reach this household's figures</b> — the test that proves
 * control 1 of docs/features/reporting.md's threat model. A cross-household {@code 404} test cannot
 * prove this, because a report takes no id.
 *
 * <p><b>Why this needs more than a second household.</b> {@code uq_households_singleton} permits
 * one household row whoever is inserting, so a second one is forced into existence by dropping the
 * index and restoring it afterwards, as {@code AccountHouseholdScopingIT} does. The seeding is
 * plain SQL: the repository is the suspect, and a test that used it to set up would be asking it
 * for an alibi.
 *
 * <p><b>And why it needs more than a realistic second household.</b> Every report query predicates
 * <em>every</em> table on the household: {@code transactions}, {@code accounts}, {@code categories}
 * and the parent category. With the database's own guards in place — the composite foreign keys
 * from a transaction to its account and category, and {@code trg_categories_two_levels} for a
 * parent — each row only ever joins rows of its own household, so those predicates are redundant
 * with one another, and removing any <em>one</em> of them changes no figure. {@link
 * #aSecondHouseholdWithTheSameNamesMonthsAndCurrencyChangesNoFigure} catches the loss of the
 * predicate the whole query hangs on; it cannot tell whether the others are there.
 *
 * <p>So {@link #eachTablesOwnPredicateKeepsOutTheRowThatOnlyItCanStop} lifts those guards — drops
 * the two composite foreign keys and disables the trigger, as noisily as the singleton index is
 * dropped, and restores all three afterwards — and seeds one row per predicate that <b>only that
 * predicate</b> can keep out: another household's transaction on this household's account; this
 * household's transaction on another household's account; one filed under another household's
 * category; and one filed under a category nested in another household's parent. That is the
 * situation the predicates exist for: a guard that a future migration weakens, or a query that
 * joins a table the keys do not cover. Remove any one predicate and that test fails, which is the
 * proof the feature doc asks for.
 */
@SpringBootTest
class ReportHouseholdScopingIT extends PersistenceTestBase {

    private static final YearMonth JULY = YearMonth.of(2026, 7);
    private static final YearMonth AUGUST = YearMonth.of(2026, 8);

    @Autowired private ReportService reports;

    private final Seeded ours = new Seeded();
    private final Seeded theirs = new Seeded();

    /** One household's ids. */
    private static final class Seeded {
        UUID household = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        UUID salary = UUID.randomUUID();
        UUID groceries = UUID.randomUUID();
        UUID bills = UUID.randomUUID();
        UUID utilities = UUID.randomUUID();
    }

    @BeforeEach
    void seedOneHousehold() {
        // IF EXISTS so a previous run that failed to restore cannot cascade into a confusing
        // failure here instead of where the problem is.
        jdbc.execute("DROP INDEX IF EXISTS uq_households_singleton");
        transaction.executeWithoutResult(status -> seedHousehold(ours, "Lovelace", "ada"));
        seedLedger(ours, "1");
    }

    @AfterEach
    void restoreEveryGuard() {
        // Households first: neither the index nor the foreign keys can be rebuilt over rows that
        // violate them, and if this ever failed to restore them every later test in the JVM would
        // run without ADR-0026's guarantee or ADR-0017's keys. So every step is attempted even
        // when an earlier one fails, each is idempotent, and any failure is rethrown at the end:
        // a guard is never left off silently because the step before it threw.
        List<RuntimeException> failures = new ArrayList<>();
        attempt(failures, () -> jdbc.update("DELETE FROM households"));
        attempt(
                failures,
                () ->
                        jdbc.execute(
                                "ALTER TABLE categories ENABLE TRIGGER trg_categories_two_levels"));
        attempt(
                failures,
                () ->
                        jdbc.execute(
                                """
                                ALTER TABLE transactions
                                    DROP CONSTRAINT IF EXISTS fk_transactions_account,
                                    ADD CONSTRAINT fk_transactions_account
                                    FOREIGN KEY (account_id, household_id)
                                    REFERENCES accounts (id, household_id)
                                    ON DELETE RESTRICT
                                """));
        attempt(
                failures,
                () ->
                        jdbc.execute(
                                """
                                ALTER TABLE transactions
                                    DROP CONSTRAINT IF EXISTS fk_transactions_category,
                                    ADD CONSTRAINT fk_transactions_category
                                    FOREIGN KEY (category_id, household_id)
                                    REFERENCES categories (id, household_id)
                                    ON DELETE SET NULL (category_id)
                                """));
        attempt(
                failures,
                () ->
                        jdbc.execute(
                                "CREATE UNIQUE INDEX IF NOT EXISTS uq_households_singleton"
                                        + " ON households ((true))"));
        if (!failures.isEmpty()) {
            IllegalStateException failed =
                    new IllegalStateException(
                            "Could not restore every database guard after a scoping test; later"
                                    + " tests in this JVM would run without them",
                            failures.get(0));
            failures.stream().skip(1).forEach(failed::addSuppressed);
            throw failed;
        }
    }

    private static void attempt(List<RuntimeException> failures, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }

    @Test
    void bothHouseholdsReallyAreInTheDatabase() {
        // Without this, every assertion below would also pass against a database holding one
        // household, which is the way a scoping test most often ends up proving nothing at all.
        transaction.executeWithoutResult(status -> seedHousehold(theirs, "Hopper", "grace"));
        seedLedger(theirs, "7");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM households", Long.class))
                .isEqualTo(2L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(DISTINCT household_id) FROM transactions",
                                Long.class))
                .isEqualTo(2L);
    }

    @Test
    void aSecondHouseholdWithTheSameNamesMonthsAndCurrencyChangesNoFigure() {
        // given: this household's reports, read before anyone else exists
        String incomeBefore = render(reports.incomeAndSpending(ours.owner, JULY, AUGUST));
        String spendingBefore = render(reports.spendingByCategory(ours.owner, AUGUST, AUGUST));

        // when: a second household with the same category names, the same months, the same
        // currency, and different figures
        transaction.executeWithoutResult(status -> seedHousehold(theirs, "Hopper", "grace"));
        seedLedger(theirs, "7");

        // then: to the last digit
        assertThat(render(reports.incomeAndSpending(ours.owner, JULY, AUGUST)))
                .isEqualTo(incomeBefore);
        assertThat(render(reports.spendingByCategory(ours.owner, AUGUST, AUGUST)))
                .isEqualTo(spendingBefore);
        assertThat(render(reports.incomeAndSpending(theirs.owner, JULY, AUGUST)))
                .as("and theirs is really there, and different")
                .isNotEqualTo(incomeBefore);
    }

    @Test
    void eachTablesOwnPredicateKeepsOutTheRowThatOnlyItCanStop() {
        // given: both households, and this household's reports before anything crosses
        transaction.executeWithoutResult(status -> seedHousehold(theirs, "Hopper", "grace"));
        seedLedger(theirs, "7");
        String incomeBefore = render(reports.incomeAndSpending(ours.owner, AUGUST, AUGUST));
        String spendingBefore = render(reports.spendingByCategory(ours.owner, AUGUST, AUGUST));

        // when: the database's own cross-household guards are lifted, and one row per predicate
        // is written that only that predicate can keep out
        jdbc.execute("ALTER TABLE transactions DROP CONSTRAINT fk_transactions_account");
        jdbc.execute("ALTER TABLE transactions DROP CONSTRAINT fk_transactions_category");
        jdbc.execute("ALTER TABLE categories DISABLE TRIGGER trg_categories_two_levels");

        // transactions.household_id: THEIR rows on OUR account in OUR category, and uncategorised.
        // The account and category predicates both pass; only the transaction's own stops them.
        transactionRow(theirs.household, ours.account, ours.groceries, "2026-08-05", "-4100.0000");
        transactionRow(theirs.household, ours.account, null, "2026-08-06", "-4200.0000");
        transactionRow(theirs.household, ours.account, ours.salary, "2026-08-07", "4300.0000");

        // accounts.household_id: OUR row on THEIR account. Only the account's predicate stops it.
        transactionRow(ours.household, theirs.account, ours.groceries, "2026-08-08", "-4400.0000");

        // categories.household_id: OUR row filed under THEIR category.
        transactionRow(ours.household, ours.account, theirs.groceries, "2026-08-09", "-4500.0000");

        // The parent's household_id: OUR category nested under THEIR parent, with OUR row in it,
        // in July — so it is in spending-by-category's comparison range, and outside the August
        // income-and-spending read, which never joins a parent and is right to count it.
        UUID nested = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO categories (id, household_id, name, kind, parent_id)
                VALUES (?, ?, 'Nested', 'EXPENSE', ?)
                """,
                nested,
                ours.household,
                theirs.bills);
        transactionRow(ours.household, ours.account, nested, "2026-07-10", "-4600.0000");

        // then: nothing moved
        assertThat(render(reports.incomeAndSpending(ours.owner, AUGUST, AUGUST)))
                .isEqualTo(incomeBefore);
        assertThat(render(reports.spendingByCategory(ours.owner, AUGUST, AUGUST)))
                .as("and no name of theirs is printed in our tree")
                .isEqualTo(spendingBefore)
                .doesNotContain("Hopper");
    }

    // -------------------------------------------------------------------------------- seeding

    private void seedHousehold(Seeded h, String name, String email) {
        jdbc.update(
                "INSERT INTO users (id, email, display_name) VALUES (?, ?, ?)",
                h.owner,
                email + "@example.com",
                name);
        jdbc.update(
                "INSERT INTO households (id, name, base_currency) VALUES (?, ?, 'GBP')",
                h.household,
                name);
        jdbc.update(
                """
                INSERT INTO household_members (id, household_id, user_id, role)
                VALUES (?, ?, ?, 'OWNER')
                """,
                UUID.randomUUID(),
                h.household,
                h.owner);
        jdbc.update(
                """
                INSERT INTO accounts (id, household_id, name, type, currency, opening_balance)
                VALUES (?, ?, 'Current', 'CHECKING', 'GBP', 0)
                """,
                h.account,
                h.household);
        category(h.salary, h.household, "Salary", "INCOME", null);
        category(h.groceries, h.household, "Groceries", "EXPENSE", null);
        // The parent's name carries the household's, so a leaked parent is visible in the tree.
        category(h.bills, h.household, "Bills " + name, "EXPENSE", null);
        category(h.utilities, h.household, "Utilities", "EXPENSE", h.bills);
    }

    /**
     * The same shape of ledger in each household, with every amount's leading digit set by {@code
     * digit} so the two households' figures differ everywhere.
     */
    private void seedLedger(Seeded h, String digit) {
        transactionRow(h.household, h.account, h.salary, "2026-07-01", digit + "000.0000");
        transactionRow(h.household, h.account, h.groceries, "2026-07-14", "-" + digit + "1.2500");
        transactionRow(h.household, h.account, h.salary, "2026-08-01", digit + "100.0000");
        transactionRow(h.household, h.account, h.groceries, "2026-08-03", "-" + digit + "2.5000");
        transactionRow(h.household, h.account, h.utilities, "2026-08-04", "-" + digit + "3.7500");
        transactionRow(h.household, h.account, h.bills, "2026-08-05", "-" + digit + "4.0001");
        transactionRow(h.household, h.account, null, "2026-08-06", "-" + digit + "5.1000");
        transactionRow(h.household, h.account, null, "2026-08-07", digit + "6.0000");
    }

    private void category(UUID id, UUID household, String name, String kind, UUID parent) {
        jdbc.update(
                """
                INSERT INTO categories (id, household_id, name, kind, parent_id)
                VALUES (?, ?, ?, ?, ?)
                """,
                id,
                household,
                name,
                kind,
                parent);
    }

    private void transactionRow(
            UUID household, UUID account, UUID category, String date, String amount) {
        jdbc.update(
                """
                INSERT INTO transactions (id, household_id, account_id, date, amount, category_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                household,
                account,
                LocalDate.parse(date),
                new BigDecimal(amount),
                category);
    }

    // ------------------------------------------------------------------------------ rendering

    /**
     * Every figure and name in a report, as text. The records' own {@code toString} is redacted on
     * purpose, which makes it useless for a failure message — this is what a reader needs to see
     * which figure moved.
     */
    private static String render(IncomeAndSpendingReport report) {
        StringBuilder out = new StringBuilder();
        for (IncomeAndSpendingReport.CurrencyFigures c : report.currencies()) {
            out.append(c.currency())
                    .append(" average=")
                    .append(c.averageKept())
                    .append(" best=")
                    .append(c.bestMonth().map(b -> b.period() + "/" + b.kept()).orElse("-"))
                    .append(" complete=")
                    .append(c.completeMonths())
                    .append(" uncategorised=")
                    .append(c.uncategorisedCount())
                    .append(" rows=")
                    .append(c.transactionCount())
                    .append('\n');
            for (IncomeAndSpendingReport.MonthFigures m : c.months()) {
                out.append("  ")
                        .append(m.period())
                        .append(" in=")
                        .append(m.income())
                        .append(" out=")
                        .append(m.spending())
                        .append(" uIn=")
                        .append(m.uncategorisedIn())
                        .append(" uOut=")
                        .append(m.uncategorisedOut())
                        .append(" kept=")
                        .append(m.kept())
                        .append('\n');
            }
        }
        return out.toString();
    }

    private static String render(SpendingByCategoryReport report) {
        StringBuilder out = new StringBuilder();
        for (SpendingByCategoryReport.CurrencySpending c : report.currencies()) {
            out.append(c.currency())
                    .append(" spending=")
                    .append(c.spending())
                    .append(" compared=")
                    .append(c.comparedSpending())
                    .append(" uncategorised=")
                    .append(c.uncategorised().spending())
                    .append('/')
                    .append(c.uncategorised().count())
                    .append('\n');
            for (SpendingByCategoryReport.CategorySpending row : c.categories()) {
                render(out, row, "  ");
                for (SpendingByCategoryReport.CategorySpending child : row.children()) {
                    render(out, child, "    ");
                }
            }
        }
        return out.toString();
    }

    private static void render(
            StringBuilder out, SpendingByCategoryReport.CategorySpending row, String indent) {
        out.append(indent)
                .append(row.name())
                .append(" spending=")
                .append(row.spending())
                .append(" compared=")
                .append(row.comparedSpending())
                .append(" direct=")
                .append(row.direct())
                .append(" change=")
                .append(row.change())
                .append(" share=")
                .append(row.share())
                .append('\n');
    }
}
