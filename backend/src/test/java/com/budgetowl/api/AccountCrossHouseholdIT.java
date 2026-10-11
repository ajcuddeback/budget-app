package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>Another household's account is indistinguishable from one that never existed.</b>
 *
 * <p>{@code AccountHouseholdScopingIT} proves this of the repository. This proves it of the API, at
 * the layer an attacker actually reaches: a real signed-in owner of the real household, sending a
 * real account id that belongs to somebody else, on every one of the seven routes. The answer is
 * {@code 404} every time and never {@code 403}, because a {@code 403} would confirm the row exists
 * (ADR-0008 as amended by ADR-0017, docs/architecture/security-model.md).
 *
 * <p>docs/features/accounts.md calls this "the valuable one", and it is the test ADR-0026 took away
 * from slice 2 — there was no second household for it to prove anything against. With accounts
 * there is financial data to leak, so it comes back.
 *
 * <p><b>Seeding a second household takes more than an {@code INSERT}.</b> {@code
 * uq_households_singleton} (V3__households.sql) is a unique index on a constant expression, so it
 * permits exactly one household row whoever is inserting — API, migration, CLI or {@code psql}. The
 * shape below is copied from {@code AccountHouseholdScopingIT} rather than rediscovered: drop the
 * index, seed, and restore it with the extra household deleted first, because the index cannot be
 * rebuilt over two rows. Deliberately noisy about doing so.
 */
class AccountCrossHouseholdIT extends ApiTestBase {

    private static final String THEIR_HOUSEHOLD = "Hopper";

    @Autowired private PlatformTransactionManager transactionManager;

    @AfterEach
    void restoreTheSingletonIndex() {
        // Both halves matter. If this ever failed to put the index back, every later test in the
        // JVM would run without ADR-0026's guarantee.
        jdbc.update("DELETE FROM households WHERE name = ?", THEIR_HOUSEHOLD);
        jdbc.execute("DROP INDEX IF EXISTS uq_households_singleton");
        jdbc.execute("CREATE UNIQUE INDEX uq_households_singleton ON households ((true))");
    }

    /** The six routes that name an account, each with a body that would otherwise be valid. */
    static List<AccountIT.Endpoint> everyRouteNamingAnAccount() {
        return AccountIT.everyRoute().stream()
                .filter(endpoint -> endpoint.path().contains("{id}"))
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRouteNamingAnAccount")
    void answers404AndNever403ForAnotherHouseholdsAccount(AccountIT.Endpoint endpoint) {
        Household household = seedHousehold();
        UUID theirs = seedASecondHouseholdWithOneAccount();

        ApiResponse response = endpoint.callWith(household.owner(), theirs);

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.stringField("code")).isEqualTo("not-found");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRouteNamingAnAccount")
    void answers404OnTheBearerTransportToo(AccountIT.Endpoint endpoint) {
        seedHousehold();
        UUID theirs = seedASecondHouseholdWithOneAccount();
        ApiClient phone = mobile(OWNER_EMAIL, OWNER_SECRET, "Ada's phone");

        ApiResponse response = endpoint.callWith(phone, theirs);

        assertThat(response.status())
                .as("neither transport may skip a check the other applies (ADR-0018)")
                .isEqualTo(404);
    }

    @Test
    void doesNotLeakAnotherHouseholdsAccountIntoTheList() {
        Household household = seedHousehold();
        seedASecondHouseholdWithOneAccount();
        createAccount(household.owner(), "Ours", "4412");

        ApiResponse listed = household.owner().get("/api/accounts?includeArchived=true");

        assertThat(listed.body()).contains("Ours").doesNotContain("Grace's savings");
        assertThat(listed.numberField("totalElements")).isEqualTo(1);
    }

    @Test
    void doesNotCountAnotherHouseholdsAccountsInTheEmptyState() {
        // Without this, "the household has no accounts" could be answered by somebody else's row
        // and the first-run empty state would never appear.
        Household household = seedHousehold();
        seedASecondHouseholdWithOneAccount();

        ApiResponse listed = household.owner().get("/api/accounts");

        assertThat(listed.body()).contains("\"content\":[]");
        assertThat(listed.numberField("totalElements")).isZero();
    }

    @Test
    void leavesAnotherHouseholdsAccountUntouchedByARefusedWrite() {
        Household household = seedHousehold();
        UUID theirs = seedASecondHouseholdWithOneAccount();

        household.owner().patch("/api/accounts/" + theirs, "{\"version\":0,\"name\":\"Mine now\"}");
        household.owner().delete("/api/accounts/" + theirs);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT name FROM accounts WHERE id = ?", String.class, theirs))
                .isEqualTo("Grace's savings");
    }

    @Test
    void reallyHasTwoHouseholdsInTheDatabase() {
        // Without this, every assertion above would also pass against a single household — which
        // is the way a scoping test most often ends up proving nothing at all.
        seedHousehold();
        seedASecondHouseholdWithOneAccount();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM households", Long.class))
                .isEqualTo(2L);
    }

    /**
     * @return the id of the account that belongs to the other household
     */
    private UUID seedASecondHouseholdWithOneAccount() {
        // IF EXISTS so a previous run that failed to restore the index cannot cascade into a
        // confusing failure here instead of where the problem is.
        jdbc.execute("DROP INDEX IF EXISTS uq_households_singleton");

        UUID household = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID account = UUID.randomUUID();

        // A household and its first OWNER have to be committed together: ck_households_at_least
        // _one_owner is DEFERRABLE INITIALLY DEFERRED and re-reads the counter at COMMIT, so two
        // auto-committed statements would fail on the first (V3__households.sql).
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            // Plain SQL rather than the API or the repositories: those are the
                            // things under test, and a scoping test that used them to set up
                            // would be asking the suspect for its own alibi.
                            jdbc.update(
                                    "INSERT INTO users (id, email, display_name)"
                                            + " VALUES (?, ?, 'Grace')",
                                    owner,
                                    "grace-elsewhere@example.com");
                            jdbc.update(
                                    "INSERT INTO households (id, name, base_currency)"
                                            + " VALUES (?, ?, 'GBP')",
                                    household,
                                    THEIR_HOUSEHOLD);
                            jdbc.update(
                                    """
                                    INSERT INTO household_members (id, household_id, user_id, role)
                                    VALUES (?, ?, ?, 'OWNER')
                                    """,
                                    UUID.randomUUID(),
                                    household,
                                    owner);
                            jdbc.update(
                                    """
                                    INSERT INTO accounts (id, household_id, name, last_four, type,
                                                          currency, opening_balance)
                                    VALUES (?, ?, 'Grace''s savings', '9999', 'SAVINGS', 'GBP',
                                            4321.0000)
                                    """,
                                    account,
                                    household);
                        });
        return account;
    }
}
