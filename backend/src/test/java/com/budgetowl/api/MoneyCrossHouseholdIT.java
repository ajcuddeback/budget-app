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
 * <p>{@code AccountCrossHouseholdIT} does this for slice 3. This is the same test for every table
 * that holds an amount — categories and transactions, and now bills, their occurrences and income
 * sources — and it exists because the version written with the slice did not: it sent a {@code
 * UUID.randomUUID()}, which answers {@code 404} even against a completely unscoped {@code
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
    private static final String THEIR_BILL = "Grace's rent";
    private static final String THEIR_INCOME = "Grace's salary";

    /**
     * The sharpest string in this file. A servicer name discloses with no amount attached at all —
     * <em>Nelnet</em> says this household has student debt — so it is the thing to search a
     * response for.
     */
    private static final String THEIR_SERVICER = "Grace's payday lender";

    private static final String THEIR_DEBT = "Grace's loan";

    /**
     * Distinctive, because the envelope term of the summary is a <b>SUM</b> rather than a row. An
     * unscoped sum leaks a number, not a record — it reads as the household being several hundred
     * pounds worse off than it is, and nobody files a bug about that.
     */
    private static final String THEIR_ENVELOPE_AMOUNT = "654.0000";

    @Autowired private PlatformTransactionManager transactionManager;

    /** The ids of the second household's rows, filled in by {@link #seedASecondHousehold()}. */
    private record Theirs(
            UUID household,
            UUID account,
            UUID category,
            UUID transaction,
            UUID bill,
            UUID income,
            UUID envelope,
            UUID transferGroup,
            UUID debt,
            UUID debtPayment,
            UUID billOccurrence) {}

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
                .isEqualTo(3L);
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
    void answers404AndNever403ForAnotherHouseholdsBill() {
        seedHousehold();
        Theirs theirs = seedASecondHousehold();
        ApiClient us = signIn(OWNER_EMAIL, OWNER_SECRET);

        assertThat(us.get("/api/bills/" + theirs.bill() + "?period=2099-03").status())
                .isEqualTo(404);
        assertThat(
                        us.patch("/api/bills/" + theirs.bill(), "{\"name\":\"Ours\",\"version\":0}")
                                .status())
                .isEqualTo(404);
        assertThat(us.delete("/api/bills/" + theirs.bill()).status()).isEqualTo(404);
        // The occurrence routes too: a bill id in a path is a lookup key, and so is a date.
        assertThat(
                        us.put(
                                        "/api/bills/" + theirs.bill() + "/occurrences/2099-03-09",
                                        "{\"status\":\"PAID\"}")
                                .status())
                .isEqualTo(404);
        assertThat(us.delete("/api/bills/" + theirs.bill() + "/occurrences/2099-03-09").status())
                .isEqualTo(404);
    }

    @Test
    void answers404AndNever403ForAnotherHouseholdsIncomeSource() {
        seedHousehold();
        Theirs theirs = seedASecondHousehold();
        ApiClient us = signIn(OWNER_EMAIL, OWNER_SECRET);

        assertThat(us.get("/api/income/" + theirs.income() + "?period=2099-03").status())
                .isEqualTo(404);
        assertThat(us.patch("/api/income/" + theirs.income(), "{\"version\":0}").status())
                .isEqualTo(404);
        assertThat(us.delete("/api/income/" + theirs.income()).status()).isEqualTo(404);
    }

    @Test
    void doesNotLeakAnotherHouseholdsBillsOrIncomeIntoTheMonth() {
        Household household = seedHousehold();
        seedASecondHousehold();

        ApiResponse bills = household.owner().get("/api/bills?period=2099-03&includeInactive=true");
        ApiResponse income =
                household.owner().get("/api/income?period=2099-03&includeInactive=true");

        assertThat(bills.status()).isEqualTo(200);
        assertThat(bills.body()).doesNotContain(THEIR_BILL).doesNotContain("777.0000");
        assertThat(income.body()).doesNotContain(THEIR_INCOME).doesNotContain("8888.0000");
        assertThat(bills.stringField("total")).isEqualTo("0.0000");
        assertThat(income.stringField("total")).isEqualTo("0.0000");
    }

    @Test
    void doesNotCountAnotherHouseholdsPlanInWhatIsLeftToSpend() {
        Household household = seedHousehold();
        seedASecondHousehold();

        ApiResponse summary = household.owner().get("/api/periods/2099-03/summary");

        // The headline the page leads with. A leak here would not look like a leak — it would look
        // like the household being eight thousand pounds better off than it is.
        assertThat(summary.stringField("income")).isEqualTo("0.0000");
        assertThat(summary.stringField("bills")).isEqualTo("0.0000");
        // The envelope term is a SUM, so this is the assertion that fails if the household
        // predicate ever leaves `allocatedForPeriod`. Without an envelope seeded for the second
        // household it could not fail, which a security review caught.
        assertThat(summary.stringField("envelopesAllocated")).isEqualTo("0.0000");
        assertThat(summary.body()).doesNotContain(THEIR_ENVELOPE_AMOUNT);
        assertThat(summary.stringField("left")).isEqualTo("0.0000");
    }

    @Test
    void doesNotLeakAnotherHouseholdsEnvelopesIntoTheMonth() {
        Household household = seedHousehold();
        seedASecondHousehold();

        ApiResponse listed =
                household.owner().get("/api/envelopes?period=2099-03&includeArchived=true");

        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.body())
                .doesNotContain("Grace's envelope")
                .doesNotContain(THEIR_ENVELOPE_AMOUNT);
        assertThat(listed.stringField("totalAllocated")).isEqualTo("0.0000");
        assertThat(listed.numberField("totalElements")).isZero();
    }

    @Test
    void cannotFileOneOfOurBillsUnderOneOfTheirCategories() {
        Household household = seedHousehold();
        Theirs theirs = seedASecondHousehold();

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/bills",
                                """
                                {"name":"Rent","expectedAmount":"1200.00",
                                 "schedule":{"kind":"MONTHLY","dayOfMonth":15},
                                 "categoryId":"%s"}
                                """
                                        .formatted(theirs.category()));

        // Filing under their category would have leaked its name through `categoryName`.
        assertThat(refused.status()).isEqualTo(404);
    }

    @Test
    void cannotPointOneOfOurIncomeSourcesAtOneOfTheirAccounts() {
        Household household = seedHousehold();
        Theirs theirs = seedASecondHousehold();

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/income",
                                """
                                {"name":"Salary","amount":"2400.00",
                                 "schedule":{"kind":"MONTHLY","dayOfMonth":28},
                                 "accountId":"%s"}
                                """
                                        .formatted(theirs.account()));

        assertThat(refused.status()).isEqualTo(404);
    }

    @Test
    void leavesAnotherHouseholdsOccurrenceUntouchedByARefusedWrite() {
        Household household = seedHousehold();
        Theirs theirs = seedASecondHousehold();

        household
                .owner()
                .put(
                        "/api/bills/" + theirs.bill() + "/occurrences/2099-03-09",
                        "{\"status\":\"PAID\"}");
        household.owner().delete("/api/bills/" + theirs.bill() + "/occurrences/2099-03-09");

        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM bill_occurrences WHERE bill_id = ?",
                                String.class,
                                theirs.bill()))
                .as("their record of their own month, exactly as it was")
                .isEqualTo("DUE");
    }

    @Test
    void answers404AndNever403ForAnotherHouseholdsTransfer() {
        seedHousehold();
        Theirs theirs = seedASecondHousehold();
        ApiClient us = signIn(OWNER_EMAIL, OWNER_SECRET);

        // 404, not 403 and not 500. Removing the household predicate from findTransferLegs makes
        // these 500 rather than leak, because TransferService refuses to name an account this
        // household cannot read — the same second layer TransactionService.view has. Asserting
        // the status first is what tells a broken scope apart from a disclosure.
        assertThat(us.get("/api/transfers/" + theirs.transferGroup()).status()).isEqualTo(404);
        assertThat(
                        us.patch(
                                        "/api/transfers/" + theirs.transferGroup(),
                                        "{\"amount\":\"1.00\",\"version\":0}")
                                .status())
                .isEqualTo(404);
        assertThat(us.delete("/api/transfers/" + theirs.transferGroup()).status()).isEqualTo(404);

        // And their pair is exactly as it was: a refused write must touch nothing.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM transactions WHERE transfer_group_id = ?",
                                Long.class,
                                theirs.transferGroup()))
                .isEqualTo(2L);
    }

    @Test
    void cannotMoveMoneyIntoOrOutOfAnotherHouseholdsAccount() {
        Household household = seedHousehold();
        Theirs theirs = seedASecondHousehold();
        UUID ours = createAccount(household.owner(), "Ours", null);

        // The side door this check exists for: a transfer touches TWO accounts, so taking one of
        // them on trust from the other would be a way to write a row into an account the caller
        // cannot otherwise write to — and then to read its name back off the response.
        assertThat(moveMoney(household.owner(), ours, theirs.account()).status()).isEqualTo(404);
        assertThat(moveMoney(household.owner(), theirs.account(), ours).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Long.class))
                .as("their ledger must be exactly as long as it was")
                .isEqualTo(3L);
    }

    @Test
    void doesNotLeakAnotherHouseholdsTransferIntoOurLedger() {
        Household household = seedHousehold();
        seedASecondHousehold();

        ApiResponse ledger = household.owner().get("/api/transactions");

        assertThat(ledger.status()).isEqualTo(200);
        assertThat(ledger.body()).doesNotContain("543.0000").doesNotContain("Grace's savings");
        assertThat(ledger.numberField("totalElements")).isZero();
    }

    static List<DebtIT.Endpoint> everyRouteNamingADebt() {
        return DebtIT.everyRoute().stream()
                .filter(endpoint -> endpoint.path().contains("{id}"))
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRouteNamingADebt")
    void answers404AndNever403ForAnotherHouseholdsDebt(DebtIT.Endpoint endpoint) {
        seedHousehold();
        Theirs theirs = seedASecondHousehold();

        ApiResponse response = endpoint.callWith(signIn(OWNER_EMAIL, OWNER_SECRET), theirs.debt());

        // 404 for both a missing row and one belonging to somebody else, as everywhere since
        // ADR-0008. There is no oracle to build.
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.stringField("code")).isEqualTo("not-found");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRouteNamingADebt")
    void answers404ForAnotherHouseholdsDebtOnTheBearerTransportToo(DebtIT.Endpoint endpoint) {
        seedHousehold();
        Theirs theirs = seedASecondHousehold();
        ApiClient phone = mobile(OWNER_EMAIL, OWNER_SECRET, "Ada's phone");

        assertThat(endpoint.callWith(phone, theirs.debt()).status())
                .as("neither transport may skip a check the other applies (ADR-0018)")
                .isEqualTo(404);
    }

    @Test
    void answers404ForAnotherHouseholdsDebtPayment() {
        seedHousehold();
        Theirs theirs = seedASecondHousehold();
        UUID ours =
                createDebt(
                        signIn(OWNER_EMAIL, OWNER_SECRET),
                        "Ours",
                        "100.00",
                        "1.000",
                        "10.00",
                        null);
        ApiClient us = signIn(OWNER_EMAIL, OWNER_SECRET);

        // Their payment id under OUR debt: the pair has to be scoped, not just the debt. A query
        // that resolved the payment by id alone would edit a row in another household through a
        // path that looks entirely ordinary.
        assertThat(
                        us.patch(
                                        "/api/debts/" + ours + "/payments/" + theirs.debtPayment(),
                                        "{\"amount\":\"1.0000\",\"version\":0}")
                                .status())
                .isEqualTo(404);
        assertThat(us.delete("/api/debts/" + ours + "/payments/" + theirs.debtPayment()).status())
                .isEqualTo(404);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT amount::text FROM debt_payments WHERE id = ?",
                                String.class,
                                theirs.debtPayment()))
                .as("their record of their own payment, exactly as it was")
                .isEqualTo("342.0000");
    }

    @Test
    void doesNotLeakAnotherHouseholdsDebtsIntoOurListOrOurPlan() {
        Household household = seedHousehold();
        seedASecondHousehold();

        ApiResponse listed = household.owner().get("/api/debts?includeArchived=true");
        ApiResponse plan = household.owner().get("/api/debt-plan");

        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.body())
                .doesNotContain(THEIR_DEBT)
                .doesNotContain(THEIR_SERVICER)
                .doesNotContain("9876.0000");
        assertThat(listed.stringField("owed")).isEqualTo("0.0000");
        assertThat(listed.stringField("debtMinimums")).isEqualTo("0.0000");
        // The plan is a statement of disposable income, and the strategy is a choice somebody
        // made. One household per instance means this reads from OUR plan row or from none.
        assertThat(plan.body()).doesNotContain("1234.0000").doesNotContain("SNOWBALL");
        assertThat(plan.numberField("debtCount")).isZero();
    }

    @Test
    void doesNotCountAnotherHouseholdsDebtMinimumsInWhatIsLeftToSpend() {
        Household household = seedHousehold();
        seedASecondHousehold();

        ApiResponse summary = household.owner().get("/api/periods/2099-03/summary");

        // A leak here would not look like a leak. It would look like the household being £342 a
        // month worse off than it is, which nobody files a bug about.
        assertThat(summary.stringField("owed")).isEqualTo("0.0000");
        assertThat(summary.stringField("debtMinimums")).isEqualTo("0.0000");
        assertThat(summary.stringField("debtMinimumsNotInBills")).isEqualTo("0.0000");
        assertThat(summary.numberField("debtCount")).isZero();
        assertThat(summary.stringField("left")).isEqualTo("0.0000");
    }

    @Test
    void cannotBindOneOfOurDebtsToAnotherHouseholdsAccountBillOrTransaction() {
        Household household = seedHousehold();
        Theirs theirs = seedASecondHousehold();
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();

        ApiResponse foreignAccount =
                household
                        .owner()
                        .post(
                                "/api/debts",
                                """
                                {"name":"Ours","kind":"CREDIT_CARD","balance":"100.0000",
                                 "balanceDate":"%s","apr":"1.000","minimumPayment":"10.0000",
                                 "accountId":"%s"}
                                """
                                        .formatted(today, theirs.account()));
        ApiResponse foreignBill =
                household
                        .owner()
                        .post(
                                "/api/debts",
                                """
                                {"name":"Ours","kind":"CREDIT_CARD","balance":"100.0000",
                                 "balanceDate":"%s","apr":"1.000","minimumPayment":"10.0000",
                                 "billId":"%s"}
                                """
                                        .formatted(today, theirs.bill()));

        // Binding to their account would leak its currency and its existence through our own debt
        // screen; binding to their bill would subtract their minimum from our month.
        assertThat(foreignAccount.status()).isEqualTo(404);
        assertThat(foreignBill.status()).isEqualTo(404);

        UUID ours = createDebt(household.owner(), "Ours", "100.00", "1.000", "10.00", null);
        ApiResponse foreignTransaction =
                household
                        .owner()
                        .post(
                                "/api/debts/" + ours + "/payments",
                                """
                                {"paidDate":"%s","amount":"10.0000","transactionId":"%s"}
                                """
                                        .formatted(today, theirs.transaction()));

        ApiResponse foreignOccurrence =
                household
                        .owner()
                        .post(
                                "/api/debts/" + ours + "/payments",
                                """
                                {"paidDate":"%s","amount":"10.0000","billOccurrenceId":"%s"}
                                """
                                        .formatted(today, theirs.billOccurrence()));
        ApiResponse foreignPaidFrom =
                household
                        .owner()
                        .post(
                                "/api/debts/" + ours + "/payments",
                                """
                                {"paidDate":"%s","amount":"10.0000","paidFromAccountId":"%s"}
                                """
                                        .formatted(today, theirs.account()));

        // The same shape for all four ids a payment or a debt may name. The feature doc claimed
        // the occurrence was covered and no test proved it; the paid-from account is new.
        assertThat(foreignTransaction.status()).isEqualTo(404);
        assertThat(foreignOccurrence.status()).isEqualTo(404);
        assertThat(foreignPaidFrom.status()).isEqualTo(404);

        // And through PATCH, which is a different branch of the same control: Change.SetTo.
        long version = household.owner().get("/api/debts/" + ours).numberField("version");
        assertThat(
                        household
                                .owner()
                                .patch(
                                        "/api/debts/" + ours,
                                        "{\"accountId\":\""
                                                + theirs.account()
                                                + "\",\"version\":"
                                                + version
                                                + "}")
                                .status())
                .isEqualTo(404);
        assertThat(
                        household
                                .owner()
                                .patch(
                                        "/api/debts/" + ours,
                                        "{\"billId\":\""
                                                + theirs.bill()
                                                + "\",\"version\":"
                                                + version
                                                + "}")
                                .status())
                .isEqualTo(404);
        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT count(*) FROM debts
                                WHERE household_id <> ?
                                  AND (account_id IS NOT NULL OR bill_id IS NOT NULL)
                                """,
                                Long.class,
                                theirs.household()))
                .as("no debt of ours ended up bound to anything of theirs")
                .isZero();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM debt_payments", Long.class))
                .as("only their own seeded payment exists")
                .isEqualTo(1L);
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
                .as("their plain transaction, plus the two legs of their transfer")
                .isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bills", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM income_sources", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM envelopes", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM debts", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM debt_payments", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payoff_plans", Long.class))
                .isEqualTo(1L);
    }

    /** Seeds a whole second household — user, membership, account, category, payee, transaction. */
    /** A transfer attempt, as the caller's own household. */
    private ApiResponse moveMoney(ApiClient client, UUID from, UUID to) {
        return client.post(
                "/api/transfers",
                """
                {"fromAccountId":"%s","toAccountId":"%s","date":"2026-08-12","amount":"1.00"}
                """
                        .formatted(from, to));
    }

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
        UUID bill = UUID.randomUUID();
        UUID income = UUID.randomUUID();
        UUID envelope = UUID.randomUUID();
        UUID transferGroup = UUID.randomUUID();
        UUID secondAccount = UUID.randomUUID();
        UUID debt = UUID.randomUUID();
        UUID debtPayment = UUID.randomUUID();
        UUID billOccurrence = UUID.randomUUID();

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
                            jdbc.update(
                                    """
                                    INSERT INTO bills (id, household_id, name, expected_amount,
                                                       schedule, day_of_month)
                                    VALUES (?, ?, ?, 777.0000, 'MONTHLY', 9)
                                    """,
                                    bill,
                                    household,
                                    THEIR_BILL);
                            jdbc.update(
                                    """
                                    INSERT INTO bill_occurrences (id, household_id, bill_id, period,
                                                                  due_date, expected_amount, status)
                                    VALUES (?, ?, ?, DATE '2099-03-01', DATE '2099-03-09',
                                            777.0000, 'DUE')
                                    """,
                                    billOccurrence,
                                    household,
                                    bill);
                            jdbc.update(
                                    """
                                    INSERT INTO income_sources (id, household_id, name, amount,
                                                                schedule, day_of_month)
                                    VALUES (?, ?, ?, 8888.0000, 'MONTHLY', 28)
                                    """,
                                    income,
                                    household,
                                    THEIR_INCOME);
                            jdbc.update(
                                    """
                                    INSERT INTO envelopes (id, household_id, name, period, amount)
                                    VALUES (?, ?, 'Grace''s envelope', DATE '2099-03-01',
                                            654.0000)
                                    """,
                                    envelope,
                                    household);
                            // A second account and a whole transfer, so the pair routes have
                            // something real to be refused against rather than a random id.
                            jdbc.update(
                                    """
                                    INSERT INTO accounts (id, household_id, name, type, currency,
                                                          opening_balance)
                                    VALUES (?, ?, 'Grace''s savings', 'SAVINGS', 'GBP', 0.0000)
                                    """,
                                    secondAccount,
                                    household);
                            jdbc.update(
                                    """
                                    INSERT INTO transactions (id, household_id, account_id, date,
                                                              amount, transfer_group_id)
                                    VALUES (?, ?, ?, DATE '2026-07-02', -543.0000, ?)
                                    """,
                                    UUID.randomUUID(),
                                    household,
                                    account,
                                    transferGroup);
                            jdbc.update(
                                    """
                                    INSERT INTO transactions (id, household_id, account_id, date,
                                                              amount, transfer_group_id)
                                    VALUES (?, ?, ?, DATE '2026-07-02', 543.0000, ?)
                                    """,
                                    UUID.randomUUID(),
                                    household,
                                    secondAccount,
                                    transferGroup);
                            // A debt and a payment against it, with a servicer name and a
                            // balance distinctive enough that a leak would be visible in any
                            // response body — and real rows rather than random ids, because a
                            // random UUID answers 404 against a completely unscoped findById.
                            jdbc.update(
                                    """
                                    INSERT INTO debts (id, household_id, name, kind, servicer,
                                                       currency, apr, minimum_payment,
                                                       anchor_balance, anchor_date, balance_source,
                                                       bill_id, account_id)
                                    VALUES (?, ?, ?, 'INSTALLMENT', ?, 'GBP', 29.900, 342.0000,
                                            9876.0000, DATE '2026-07-01', 'CONFIRMED', ?, ?)
                                    """,
                                    debt,
                                    household,
                                    THEIR_DEBT,
                                    THEIR_SERVICER,
                                    bill,
                                    account);
                            jdbc.update(
                                    """
                                    INSERT INTO debt_payments (id, household_id, debt_id, paid_date,
                                                               amount, interest, principal,
                                                               balance_after, provenance,
                                                               transaction_id)
                                    VALUES (?, ?, ?, DATE '2026-07-15', 342.0000, 42.0000,
                                            300.0000, 9576.0000, 'USER_LOGGED', ?)
                                    """,
                                    debtPayment,
                                    household,
                                    debt,
                                    transaction);
                            jdbc.update(
                                    """
                                    INSERT INTO payoff_plans (id, household_id, strategy,
                                                              monthly_amount)
                                    VALUES (?, ?, 'SNOWBALL', 1234.0000)
                                    """,
                                    UUID.randomUUID(),
                                    household);
                        });
        return new Theirs(
                household,
                account,
                category,
                transaction,
                bill,
                income,
                envelope,
                transferGroup,
                debt,
                debtPayment,
                billOccurrence);
    }
}
