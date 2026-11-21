package com.budgetowl.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The transfer pair rule, exercised against the database rather than against the service that is
 * supposed to respect it.
 *
 * <p>"The application validates for good error messages; the database enforces truth"
 * (docs/guides/database-style.md). Every row here is written in <b>plain SQL</b>, bypassing {@code
 * TransferService} entirely — which is the point: if these only failed through the service they
 * would prove the service, not the constraint, and the constraint is what still holds when a future
 * importer or a hand-run statement writes to this table.
 *
 * <p>The rule is deferred to {@code COMMIT}, so every case below has to complete a transaction to
 * find out. A statement that succeeds here means nothing until the commit does.
 */
@SpringBootTest
class TransferPairConstraintIT extends PersistenceTestBase {

    private UUID household;
    private UUID accountA;
    private UUID accountB;

    @BeforeEach
    void seedTwoAccounts() {
        household = UUID.randomUUID();
        accountA = UUID.randomUUID();
        accountB = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        transaction.executeWithoutResult(
                status -> {
                    jdbc.update(
                            "INSERT INTO users (id, email, display_name)"
                                    + " VALUES (?, 'ada@example.com', 'Ada')",
                            owner);
                    jdbc.update(
                            "INSERT INTO households (id, name, base_currency)"
                                    + " VALUES (?, 'Ada and Grace', 'GBP')",
                            household);
                    jdbc.update(
                            """
                            INSERT INTO household_members (id, household_id, user_id, role)
                            VALUES (?, ?, ?, 'OWNER')
                            """,
                            UUID.randomUUID(),
                            household,
                            owner);
                    insertAccount(accountA, "Current", "GBP");
                    insertAccount(accountB, "Savings", "GBP");
                });
    }

    @Test
    void acceptsAPairInTwoAccountsWithOppositeSigns() {
        UUID group = UUID.randomUUID();

        assertThatCode(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            insertLeg(group, accountA, "-250.0000");
                                            insertLeg(group, accountB, "250.0000");
                                        }))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsACrossCurrencyPairThatDoesNotSumToZero() {
        UUID euros = UUID.randomUUID();
        transaction.executeWithoutResult(status -> insertAccount(euros, "Holiday", "EUR"));
        UUID group = UUID.randomUUID();

        // £200 leaving does not make €200 arrive. A constraint demanding the amounts match would
        // forbid the honest record and force a fabricated rate (ADR-0033) — so this must pass,
        // and it is as much a part of the rule as the refusals below.
        assertThatCode(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            insertLeg(group, accountA, "-200.0000");
                                            insertLeg(group, euros, "228.4000");
                                        }))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesAGroupWithOnlyOneLeg() {
        UUID group = UUID.randomUUID();

        // The outcome the whole rule exists to prevent: money that left one account and arrived
        // nowhere. It is legal mid-transaction and illegal at COMMIT, which is why the trigger
        // is DEFERRABLE INITIALLY DEFERRED.
        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> insertLeg(group, accountA, "-250.0000")))
                .hasMessageContaining("has 1 legs");
    }

    @Test
    void refusesThreeLegs() {
        UUID group = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        transaction.executeWithoutResult(status -> insertAccount(third, "Spare", "GBP"));

        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            insertLeg(group, accountA, "-250.0000");
                                            insertLeg(group, accountB, "250.0000");
                                            insertLeg(group, third, "250.0000");
                                        }))
                .hasMessageContaining("has 3 legs");
    }

    @Test
    void refusesBothLegsInTheSameAccount() {
        UUID group = UUID.randomUUID();

        // Two rows that cancel out inside one account is not a movement between accounts, and a
        // balance would be unchanged while the ledger claimed something happened.
        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            insertLeg(group, accountA, "-250.0000");
                                            insertLeg(group, accountA, "250.0000");
                                        }))
                .hasMessageContaining("two different accounts");
    }

    @Test
    void refusesTwoLegsWithTheSameSign() {
        UUID group = UUID.randomUUID();

        // Two withdrawals wearing a transfer's badge. Nothing downstream would notice, and
        // spending would be understated by the whole amount — in both directions.
        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            insertLeg(group, accountA, "-250.0000");
                                            insertLeg(group, accountB, "-250.0000");
                                        }))
                .hasMessageContaining("one leg leaving and one arriving");
    }

    @Test
    void refusesAPairThatSpansTwoHouseholds() {
        UUID otherHousehold = UUID.randomUUID();
        UUID theirAccount = UUID.randomUUID();
        UUID group = UUID.randomUUID();

        // uq_households_singleton permits one household row, so it comes off for the duration —
        // the same shape MoneyCrossHouseholdIT uses, and for the same reason: a scoping rule
        // cannot be tested against a world with only one household in it.
        jdbc.execute("DROP INDEX IF EXISTS uq_households_singleton");
        try {
            transaction.executeWithoutResult(
                    status -> {
                        // A household and its first OWNER have to be committed together: the
                        // at-least-one-owner rule is DEFERRABLE INITIALLY DEFERRED and re-reads
                        // the counter at COMMIT.
                        UUID theirOwner = UUID.randomUUID();
                        jdbc.update(
                                "INSERT INTO users (id, email, display_name)"
                                        + " VALUES (?, 'grace-elsewhere@example.com', 'Grace')",
                                theirOwner);
                        jdbc.update(
                                "INSERT INTO households (id, name, base_currency)"
                                        + " VALUES (?, 'Hopper', 'GBP')",
                                otherHousehold);
                        jdbc.update(
                                """
                                INSERT INTO household_members (id, household_id, user_id, role)
                                VALUES (?, ?, ?, 'OWNER')
                                """,
                                UUID.randomUUID(),
                                otherHousehold,
                                theirOwner);
                        jdbc.update(
                                """
                                INSERT INTO accounts (id, household_id, name, type, currency,
                                                      opening_balance)
                                VALUES (?, ?, 'Theirs', 'CHECKING', 'GBP', 0.0000)
                                """,
                                theirAccount,
                                otherHousehold);
                    });

            // The branch with the strongest claim in the trigger, and until now the only one no
            // test exercised — so it could have been deleted as dead code with the gate staying
            // green. Each leg's own foreign key only forces it to match ITS OWN account's
            // household; nothing but this stops the two legs being in different ones.
            assertThatThrownBy(
                            () ->
                                    transaction.executeWithoutResult(
                                            status -> {
                                                insertLeg(group, accountA, "-250.0000");
                                                jdbc.update(
                                                        """
                                                        INSERT INTO transactions (id, household_id,
                                                            account_id, date, amount,
                                                            transfer_group_id)
                                                        VALUES (?, ?, ?, DATE '2026-08-12',
                                                                250.0000, ?)
                                                        """,
                                                        UUID.randomUUID(),
                                                        otherHousehold,
                                                        theirAccount,
                                                        group);
                                            }))
                    .hasMessageContaining("more than one household");
        } finally {
            // Both halves matter: leaving the index off would run every later test in this JVM
            // without ADR-0026's guarantee.
            jdbc.update("DELETE FROM transactions WHERE household_id = ?", otherHousehold);
            jdbc.update("DELETE FROM accounts WHERE household_id = ?", otherHousehold);
            jdbc.update("DELETE FROM households WHERE id = ?", otherHousehold);
            jdbc.update("DELETE FROM users WHERE email = 'grace-elsewhere@example.com'");
            jdbc.execute("DROP INDEX IF EXISTS uq_households_singleton");
            jdbc.execute("CREATE UNIQUE INDEX uq_households_singleton ON households ((true))");
        }
    }

    @Test
    void refusesATransferFiledUnderACategory() {
        UUID group = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        transaction.executeWithoutResult(
                status ->
                        jdbc.update(
                                """
                                INSERT INTO categories (id, household_id, name, kind)
                                VALUES (?, ?, 'Savings', 'EXPENSE')
                                """,
                                category,
                                household));

        // Moving your own money is not spending. A CHECK rather than a trigger, because it is a
        // fact about one row and needs no deferral.
        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            jdbc.update(
                                                    """
                                                    INSERT INTO transactions (id, household_id,
                                                        account_id, date, amount,
                                                        transfer_group_id, category_id)
                                                    VALUES (?, ?, ?, DATE '2026-08-12', -250.0000,
                                                            ?, ?)
                                                    """,
                                                    UUID.randomUUID(),
                                                    household,
                                                    accountA,
                                                    group,
                                                    category);
                                        }))
                .hasMessageContaining("ck_transactions_transfer_has_no_category");
    }

    @Test
    void allowsDeletingBothLegsTogether() {
        UUID group = UUID.randomUUID();
        transaction.executeWithoutResult(
                status -> {
                    insertLeg(group, accountA, "-250.0000");
                    insertLeg(group, accountB, "250.0000");
                });

        // Removing a transfer is removing both legs, so an empty group is not a violation —
        // without that case the trigger would make a transfer impossible to delete.
        assertThatCode(
                        () ->
                                transaction.executeWithoutResult(
                                        status ->
                                                jdbc.update(
                                                        "DELETE FROM transactions"
                                                                + " WHERE transfer_group_id = ?",
                                                        group)))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesDeletingOnlyOneLeg() {
        UUID group = UUID.randomUUID();
        transaction.executeWithoutResult(
                status -> {
                    insertLeg(group, accountA, "-250.0000");
                    insertLeg(group, accountB, "250.0000");
                });

        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status ->
                                                jdbc.update(
                                                        "DELETE FROM transactions"
                                                                + " WHERE transfer_group_id = ?"
                                                                + " AND amount < 0",
                                                        group)))
                .hasMessageContaining("has 1 legs");
    }

    // ------------------------------------------------------------------------------- internals

    private void insertAccount(UUID id, String name, String currency) {
        jdbc.update(
                """
                INSERT INTO accounts (id, household_id, name, type, currency, opening_balance)
                VALUES (?, ?, ?, 'CHECKING', ?, 0.0000)
                """,
                id,
                household,
                name,
                currency);
    }

    private void insertLeg(UUID group, UUID accountId, String amount) {
        jdbc.update(
                """
                INSERT INTO transactions (id, household_id, account_id, date, amount,
                                          transfer_group_id)
                VALUES (?, ?, ?, DATE '2026-08-12', CAST(? AS numeric), ?)
                """,
                UUID.randomUUID(),
                household,
                accountId,
                amount,
                group);
    }
}
