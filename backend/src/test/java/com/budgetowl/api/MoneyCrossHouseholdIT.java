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
 * <b>Another household's categories and transactions are indistinguishable from rows that never
 * existed.</b>
 *
 * <p>{@code AccountCrossHouseholdIT} does this for slice 3. This is the same test for the tables
 * that hold the amounts, and it exists because the version written with the slice did not: it sent
 * a {@code UUID.randomUUID()}, which answers {@code 404} even against a completely unscoped {@code
 * findById}. A test that cannot fail for the reason it was written is worse than no test, because
 * it reads in a diff as though the box is ticked. A security review caught it.
 *
 * <p>So every id here belongs to a <b>real second household</b>, seeded with plain SQL rather than
 * through the API or the repositories — those are the things under test, and a scoping test that
 * used them for its own setup would be asking the suspect for its alibi.
 *
 * <p>It also asserts what {@code docs/features/transactions.md} claims for the composite foreign
 * keys, which nothing else could: that a transaction cannot be made to reference another
 * household's account or category, because until there is a second household there is nothing to
 * reference.
 *
 * <p><b>Seeding a second household takes more than an {@code INSERT}.</b> {@code
 * uq_households_singleton} (V3) permits exactly one household row whoever is inserting. The shape
 * below is copied from {@code AccountCrossHouseholdIT}: drop the index, seed, and restore it with
 * the extra household deleted first, because it cannot be rebuilt over two rows.
 */
class MoneyCrossHouseholdIT extends ApiTestBase {

    private static final String THEIR_HOUSEHOLD = "Hopper";
    private static final String THEIR_CATEGORY = "Grace's secret category";
    private static final String THEIR_PAYEE = "Grace's bookshop";
    private static final String THEIR_NOTE = "Grace's note";

    @Autowired private PlatformTransactionManager transactionManager;

    /** The ids of the second household's rows, filled in by {@link #seedASecondHousehold()}. */
    private record Theirs(UUID household, UUID account, UUID category, UUID transaction) {}

    @AfterEach
    void restoreTheSingletonIndex() {
        // Both halves matter. If this ever failed to put the index back, every later test in the
        // JVM would run without ADR-0026's guarantee.
        jdbc.update("DELETE FROM households WHERE name = ?", THEIR_HOUSEHOLD);
        jdbc.execute("DROP INDEX IF EXISTS uq_households_singleton");
        jdbc.execute("CREATE UNIQUE INDEX uq_households_singleton ON households ((true))");
    }

    static List<CategoryIT.Endpoint> everyRouteNamingACategory() {
        return CategoryIT.everyRoute().stream()
                .filter(endpoint -> endpoint.path().contains("{id}"))
                .toList();
    }

    static List<TransactionIT.Endpoint> everyRouteNamingATransaction() {
        return TransactionIT.everyRoute().stream()
                .filter(
                        endpoint ->
                                endpoint.path().contains("{id}")
                                        && !"POST".equals(endpoint.method()))
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRouteNamingACategory")
    void answers404AndNever403ForAnotherHouseholdsCategory(CategoryIT.Endpoint endpoint) {
        seedHousehold();
        Theirs theirs = seedASecondHousehold();

        ApiResponse response =
                endpoint.callWith(signIn(OWNER_EMAIL, OWNER_SECRET), theirs.category());

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.stringField("code")).isEqualTo("not-found");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRouteNamingATransaction")
    void answers404AndNever403ForAnotherHouseholdsTransaction(TransactionIT.Endpoint endpoint) {
        seedHousehold();
        Theirs theirs = seedASecondHousehold();

        ApiResponse response =
                endpoint.callWith(signIn(OWNER_EMAIL, OWNER_SECRET), theirs.transaction());

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.stringField("code")).isEqualTo("not-found");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRouteNamingATransaction")
    void answers404OnTheBearerTransportToo(TransactionIT.Endpoint endpoint) {
        seedHousehold();
        Theirs theirs = seedASecondHousehold();
        ApiClient phone = mobile(OWNER_EMAIL, OWNER_SECRET, "Ada's phone");

        ApiResponse response = endpoint.callWith(phone, theirs.transaction());

        assertThat(response.status())
                .as("neither transport may skip a check the other applies (ADR-0018)")
                .isEqualTo(404);
    }

    @Test
    void doesNotLeakAnotherHouseholdsCategoriesIntoTheTree() {
        Household household = seedHousehold();
        seedASecondHousehold();

        ApiResponse listed = household.owner().get("/api/categories?size=200&includeArchived=true");

        assertThat(listed.body()).doesNotContain(THEIR_CATEGORY);
    }

    @Test
    void doesNotLeakAnotherHouseholdsTransactionsIntoTheLedger() {
        Household household = seedHousehold();
        seedASecondHousehold();

        ApiResponse listed = household.owner().get("/api/transactions");

        // Asserted before the body, so a broken scope reports as what it is rather than as a
        // missing field. Removing the household predicate from findLedger makes this 500 rather
        // than leak: TransactionService.view refuses to render a transaction whose account this
        // household cannot read. That second layer is why the disclosure never actually happens —
        // and this assertion is what tells the two failures apart.
        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.body())
                .doesNotContain(THEIR_PAYEE)
                .doesNotContain(THEIR_NOTE)
                .doesNotContain("4321.0000");
        assertThat(listed.numberField("totalElements")).isZero();
    }

    @Test
    void doesNotSuggestAnotherHouseholdsPayees() {
        Household household = seedHousehold();
        seedASecondHousehold();

        // The suggestion endpoint returns bare strings with no ids, so a leak here would be
        // invisible in every other assertion in this file.
        assertThat(household.owner().get("/api/transactions/payee-suggestions?typed=Grace").body())
                .isEqualTo("[]");
    }

    @Test
    void doesNotCountAnotherHouseholdsTransactionsInAnAccountsBalance() {
        Household household = seedHousehold();
        seedASecondHousehold();
        UUID ours = createAccount(household.owner(), "Ours", null);

        // The balance query sums by household AND by account. If it summed by account alone, this
        // would still pass — so the account below is deliberately a different one, and the real
        // guard is that their transaction is against their account entirely.
        assertThat(household.owner().get("/api/accounts/" + ours).stringField("balance"))
                .isEqualTo("100.0000");
    }

    @Test
    void cannotRecordATransactionAgainstAnotherHouseholdsAccount() {
        Household household = seedHousehold();
        Theirs theirs = seedASecondHousehold();

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/transactions",
                                """
                                {"accountId":"%s","date":"2026-11-20","amount":"-1.0000"}
                                """
                                        .formatted(theirs.account()));

        assertThat(refused.status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Long.class))
                .as("their ledger must be exactly as long as it was")
                .isEqualTo(1L);
    }

    @Test
    void cannotFileATransactionUnderAnotherHouseholdsCategory() {
        Household household = seedHousehold();
        Theirs theirs = seedASecondHousehold();
        UUID ours = createAccount(household.owner(), "Ours", null);

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/transactions",
                                """
                                {"accountId":"%s","date":"2026-11-20","amount":"-1.0000",
                                 "categoryId":"%s"}
                                """
                                        .formatted(ours, theirs.category()));

        assertThat(refused.status()).isEqualTo(404);
    }

    @Test
    void cannotMoveOneOfOurCategoriesUnderOneOfTheirs() {
        Household household = seedHousehold();
        Theirs theirs = seedASecondHousehold();
        UUID groceries = seededCategoryId("Groceries");
        long version = household.owner().get("/api/categories/" + groceries).numberField("version");

        ApiResponse refused =
                household
                        .owner()
                        .patch(
                                "/api/categories/" + groceries,
                                """
                                {"parentId":"%s","version":%d}
                                """
                                        .formatted(theirs.category(), version));

        // Nesting under their category would have leaked its name through `parentName`.
        assertThat(refused.status()).isEqualTo(404);
    }

    @Test
    void leavesAnotherHouseholdsTransactionUntouchedByARefusedWrite() {
        Household household = seedHousehold();
        Theirs theirs = seedASecondHousehold();

        household
                .owner()
                .patch(
                        "/api/transactions/" + theirs.transaction(),
                        "{\"version\":0,\"amount\":\"-0.0100\"}");
        household.owner().delete("/api/transactions/" + theirs.transaction());

        assertThat(
                        jdbc.queryForObject(
                                "SELECT amount::text FROM transactions WHERE id = ?",
                                String.class,
                                theirs.transaction()))
                .isEqualTo("-4321.0000");
    }

    @Test
    void reallyHasTwoHouseholdsInTheDatabase() {
        // Without this, every assertion above would also pass against a single household — which
        // is the way a scoping test most often ends up proving nothing at all.
        seedHousehold();
        seedASecondHousehold();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM households", Long.class))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Long.class))
                .isEqualTo(1L);
    }

    /** Seeds a whole second household — user, membership, account, category, payee, transaction. */
    private Theirs seedASecondHousehold() {
        // IF EXISTS so a previous run that failed to restore the index cannot cascade into a
        // confusing failure here instead of where the problem is.
        jdbc.execute("DROP INDEX IF EXISTS uq_households_singleton");

        UUID household = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID payee = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();

        // A household and its first OWNER have to be committed together: the at-least-one-owner
        // constraint is DEFERRABLE INITIALLY DEFERRED and re-reads the counter at COMMIT.
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
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
                                    INSERT INTO accounts (id, household_id, name, type, currency,
                                                          opening_balance)
                                    VALUES (?, ?, 'Grace''s current', 'CHECKING', 'GBP', 0.0000)
                                    """,
                                    account,
                                    household);
                            jdbc.update(
                                    """
                                    INSERT INTO categories (id, household_id, name, kind)
                                    VALUES (?, ?, ?, 'EXPENSE')
                                    """,
                                    category,
                                    household,
                                    THEIR_CATEGORY);
                            jdbc.update(
                                    "INSERT INTO payees (id, household_id, name) VALUES (?, ?, ?)",
                                    payee,
                                    household,
                                    THEIR_PAYEE);
                            jdbc.update(
                                    """
                                    INSERT INTO transactions (id, household_id, account_id, date,
                                                              amount, payee_id, category_id, note)
                                    VALUES (?, ?, ?, DATE '2026-07-01', -4321.0000, ?, ?, ?)
                                    """,
                                    transaction,
                                    household,
                                    account,
                                    payee,
                                    category,
                                    THEIR_NOTE);
                        });
        return new Theirs(household, account, category, transaction);
    }
}
