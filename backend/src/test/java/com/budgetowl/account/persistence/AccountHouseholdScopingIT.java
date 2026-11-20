package com.budgetowl.account.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.account.domain.Account;
import com.budgetowl.persistence.PersistenceTestBase;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

/**
 * <b>One household's accounts are unreachable from another household's id.</b> This is the highest
 * value test in slice 3.
 *
 * <p>ADR-0026 made the instance single-household, which removed the cross-household case from slice
 * 2 — there was no second household for it to prove anything against, and
 * docs/guides/testing-style.md says plainly that the same total risk then rests on fewer controls.
 * With accounts, financial data exists, so the case is worth restoring: a bug that returns the
 * wrong household's rows is a data leak, and it is invisible to every test written against a single
 * household because every query returns the right answer by accident.
 *
 * <p><b>A second household has to be forced into existence, and the feature doc is wrong about
 * that.</b> docs/features/accounts.md says "a second household can be seeded directly in the
 * database even if the UI cannot create one". It cannot: {@code uq_households_singleton}
 * (V3__households.sql) is a unique index on a constant expression, so it permits exactly one row
 * for the lifetime of the instance regardless of who is inserting — API, migration, CLI or psql.
 * That is working as designed and is a good thing. So this test drops the index, seeds two
 * households, and restores it, which is the only honest way to get a second household and is
 * deliberately noisy about doing so.
 *
 * <p>The seeding goes in through plain SQL rather than through the repositories, because the
 * repositories are the thing under test. A scoping test that used them to set up would be asking
 * the suspect to supply its own alibi.
 */
@SpringBootTest
class AccountHouseholdScopingIT extends PersistenceTestBase {

    @Autowired private AccountRepository accounts;

    private UUID householdA;
    private UUID householdB;
    private UUID accountInA;
    private UUID accountInB;

    @BeforeEach
    void seedTwoHouseholdsEachWithOneAccount() {
        // IF EXISTS so a previous run that failed to restore the index cannot cascade into a
        // confusing failure here instead of where the problem is.
        jdbc.execute("DROP INDEX IF EXISTS uq_households_singleton");

        householdA = UUID.randomUUID();
        householdB = UUID.randomUUID();
        accountInA = UUID.randomUUID();
        accountInB = UUID.randomUUID();

        // A household and its first OWNER have to be committed together (V3__households.sql).
        transaction.executeWithoutResult(
                status -> {
                    seedHousehold(householdA, "Lovelace", "ada@example.com");
                    seedHousehold(householdB, "Hopper", "grace@example.com");
                    seedAccount(accountInA, householdA, "Current");
                    seedAccount(accountInB, householdB, "Grace's savings");
                });
    }

    @AfterEach
    void restoreTheSingletonIndex() {
        // Both halves matter. The households have to go first, because the unique index cannot be
        // rebuilt over two rows — and if this method ever failed to put the index back, every
        // later test in the JVM would run without ADR-0026's guarantee.
        jdbc.update("DELETE FROM households");
        jdbc.execute("CREATE UNIQUE INDEX uq_households_singleton ON households ((true))");
    }

    @Test
    void bothHouseholdsAccountsReallyAreInTheDatabase() {
        // Without this, every assertion below would also pass against an empty table — which is
        // the way a scoping test most often ends up proving nothing at all.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM households", Long.class))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts", Long.class)).isEqualTo(2L);
    }

    @Test
    void doesNotReturnAnAccountToAHouseholdThatDoesNotOwnIt() {
        // Knowing the id is not ownership. An id from a path or a body is a lookup key and never
        // evidence of anything (ADR-0017, non-negotiable #2), and the caller turns this empty
        // Optional into a 404 rather than a 403 — a 403 would confirm the row exists.
        assertThat(accounts.findByIdAndHouseholdId(accountInB, householdA)).isEmpty();
        assertThat(accounts.findByIdAndHouseholdId(accountInA, householdB)).isEmpty();

        assertThat(accounts.findByIdAndHouseholdId(accountInA, householdA)).isPresent();
        assertThat(accounts.findByIdAndHouseholdId(accountInB, householdB)).isPresent();
    }

    @Test
    void listsOnlyTheHouseholdsOwnAccounts() {
        assertThat(accounts.findUnarchivedByHouseholdId(householdA, PageRequest.of(0, 200)))
                .extracting(Account::id)
                .containsExactly(accountInA);
        assertThat(accounts.findUnarchivedByHouseholdId(householdB, PageRequest.of(0, 200)))
                .extracting(Account::id)
                .containsExactly(accountInB);
    }

    @Test
    void includesNoOtherHouseholdsAccountsWhenArchivedRowsAreRevealed() {
        // The includeArchived listing is a second query with a second chance to drop the
        // predicate, so it gets its own assertion rather than being assumed from the first.
        jdbc.update("UPDATE accounts SET archived = true WHERE id = ?", accountInB);

        assertThat(accounts.findAllByHouseholdId(householdA, PageRequest.of(0, 200)))
                .extracting(Account::id)
                .containsExactly(accountInA);
    }

    @Test
    void countsOnlyTheHouseholdsOwnAccounts() {
        assertThat(accounts.countByHouseholdId(householdA)).isEqualTo(1);
        assertThat(accounts.countByHouseholdId(householdB)).isEqualTo(1);
    }

    @Test
    void readsTheOwningHouseholdFromTheRowRatherThanFromTheQuery() {
        Account theirs = accounts.findByIdAndHouseholdId(accountInB, householdB).orElseThrow();

        assertThat(theirs.householdId()).isEqualTo(householdB).isNotEqualTo(householdA);
    }

    @Test
    void deletingOneHouseholdLeavesTheOthersAccountsAlone() {
        // ON DELETE CASCADE removes a household's own financial data and nothing else. A cascade
        // that reached further would be the worst possible version of this bug.
        jdbc.update("DELETE FROM households WHERE id = ?", householdA);

        assertThat(accounts.findByIdAndHouseholdId(accountInA, householdA)).isEmpty();
        assertThat(accounts.findByIdAndHouseholdId(accountInB, householdB)).isPresent();
    }

    private void seedHousehold(UUID id, String name, String ownerEmail) {
        UUID owner = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, display_name) VALUES (?, ?, ?)",
                owner,
                ownerEmail,
                name);
        jdbc.update(
                "INSERT INTO households (id, name, base_currency) VALUES (?, ?, 'GBP')", id, name);
        jdbc.update(
                """
                INSERT INTO household_members (id, household_id, user_id, role)
                VALUES (?, ?, ?, 'OWNER')
                """,
                UUID.randomUUID(),
                id,
                owner);
    }

    private void seedAccount(UUID id, UUID household, String name) {
        jdbc.update(
                """
                INSERT INTO accounts (id, household_id, name, type, currency, opening_balance)
                VALUES (?, ?, ?, 'CHECKING', 'GBP', 100.0000)
                """,
                id,
                household,
                name);
    }
}
