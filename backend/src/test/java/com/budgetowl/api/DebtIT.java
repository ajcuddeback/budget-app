package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Debts, their terms and their payments, over real HTTP against real PostgreSQL.
 *
 * <p>Most of this is about the two things that would be wrong silently: <b>which figures are stored
 * and which are derived</b>, and <b>what a {@code PATCH} means when a field is absent</b>. A solved
 * field that got stored, or an absent field read as a release, both produce a page that looks
 * entirely plausible and tells the household the wrong payoff date.
 *
 * <p>The arithmetic itself is proved without a database in {@code DebtTermsSolverTest} and {@code
 * PayoffLadderTest}. What is proved here is that the right arithmetic reaches the wire with every
 * digit intact, that authorization holds on both transports, and that the links to the ledger
 * behave as ADR-0035 says.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DebtIT extends ApiTestBase {

    private static final String TODAY = LocalDate.now(ZoneOffset.UTC).toString();

    // ----------------------------------------------------------------------- the terms solver

    @Test
    void a_debt_needs_nothing_but_a_name_a_kind_a_balance_a_date_a_rate_and_a_minimum() {
        Household household = seedHousehold();

        ApiResponse created =
                household
                        .owner()
                        .post(
                                "/api/debts",
                                """
                                {"name":"Visa","kind":"CREDIT_CARD","balance":"6180.0000",
                                 "balanceDate":"%s","apr":"22.900","minimumPayment":"118.0000"}
                                """
                                        .formatted(TODAY));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.header("location")).isPresent();
        // The rate alone is enough: the payment comes from the plan, or falls back to the
        // minimum. That is what makes onboarding's three fields a complete debt.
        assertThat(created.stringField("apr")).isEqualTo("22.900");
        assertThat(created.stringField("aprSource")).isEqualTo("ENTERED");
        assertThat(created.stringField("balance")).isEqualTo("6180.0000");
        assertThat(created.stringField("balanceSource")).isEqualTo("CONFIRMED");
    }

    @Test
    void amounts_and_the_rate_keep_every_digit_across_the_wire() {
        Household household = seedHousehold();

        UUID debt =
                UUID.fromString(
                        household
                                .owner()
                                .post(
                                        "/api/debts",
                                        """
                                        {"name":"Visa","kind":"CREDIT_CARD",
                                         "balance":"6180.1234","balanceDate":"%s",
                                         "apr":"22.901","minimumPayment":"118.4400"}
                                        """
                                                .formatted(TODAY))
                                .stringField("id"));

        ApiResponse read = household.owner().get("/api/debts/" + debt);
        assertThat(read.stringField("balance")).isEqualTo("6180.1234");
        assertThat(read.stringField("apr")).isEqualTo("22.901");
        assertThat(read.stringField("minimumPayment")).isEqualTo("118.4400");
        // Strings, not JSON numbers: most parsers read a JSON number as a double, and 22.901
        // multiplies a balance (ADR-0006).
        assertThat(read.body()).contains("\"balance\":\"6180.1234\"", "\"apr\":\"22.901\"");
    }

    @Test
    void a_json_number_for_an_amount_or_a_rate_is_refused() {
        Household household = seedHousehold();

        ApiResponse numericBalance =
                household
                        .owner()
                        .post(
                                "/api/debts",
                                """
                                {"name":"Visa","kind":"CREDIT_CARD","balance":6180.00,
                                 "balanceDate":"%s","apr":"22.900","minimumPayment":"118.00"}
                                """
                                        .formatted(TODAY));
        ApiResponse numericRate =
                household
                        .owner()
                        .post(
                                "/api/debts",
                                """
                                {"name":"Visa","kind":"CREDIT_CARD","balance":"6180.00",
                                 "balanceDate":"%s","apr":22.9,"minimumPayment":"118.00"}
                                """
                                        .formatted(TODAY));

        assertThat(numericBalance.status()).isEqualTo(400);
        assertThat(numericRate.status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM debts", Long.class)).isZero();
    }

    @Test
    void over_determined_terms_are_refused_and_store_nothing() {
        Household household = seedHousehold();

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/debts",
                                """
                                {"name":"Car loan","kind":"INSTALLMENT","balance":"10650.0000",
                                 "balanceDate":"%s","apr":"6.400","minimumPayment":"342.0000",
                                 "payment":"342.0000","monthsLeft":36}
                                """
                                        .formatted(TODAY));

        // One equation among four quantities has three degrees of freedom. The answer is never to
        // drop one of the three numbers somebody typed.
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.stringField("code")).isEqualTo("terms-over-determined");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM debts", Long.class)).isZero();
    }

    @Test
    void the_check_constraint_refuses_a_third_entered_solver_field_at_the_database_level_too() {
        Household household = seedHousehold();
        UUID debt = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);

        // The edge check above is the good path. This is the half an importer, a hand-run UPDATE
        // or a caller nobody has written yet cannot bypass.
        Throwable refused =
                org.assertj.core.api.Assertions.catchThrowable(
                        () ->
                                jdbc.update(
                                        "UPDATE debts SET payment = 445.0000, months_left = 31"
                                                + " WHERE id = ?",
                                        debt));

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).contains("ck_debts_degrees_of_freedom");
    }

    @Test
    void a_released_field_becomes_null_in_the_row_and_comes_back_solved() {
        Household household = seedHousehold();
        UUID debt = createDebt(household.owner(), "Car loan", "10650.00", "6.400", "342.00", null);
        long version = household.owner().get("/api/debts/" + debt).numberField("version");

        // The client releases the rate and enters a payment and a horizon in its place: the rate
        // is now the solver's to work out. An explicit null is the release; absence would be
        // "leave it alone".
        ApiResponse released =
                household
                        .owner()
                        .patch(
                                "/api/debts/" + debt,
                                """
                                {"apr":null,"payment":"342.0000","monthsLeft":36,"version":%d}
                                """
                                        .formatted(version));

        assertThat(released.status()).isEqualTo(200);
        assertThat(released.stringField("aprSource")).isEqualTo("SOLVED");
        assertThat(released.stringField("paymentSource")).isEqualTo("ENTERED");
        assertThat(released.stringField("horizonSource")).isEqualTo("ENTERED");
        // A solved field is NULL in the row. Storing it would be a second copy of a derivable
        // fact, and the copy is what a reader would act on after the fact moved.
        assertThat(jdbc.queryForObject("SELECT apr FROM debts WHERE id = ?", String.class, debt))
                .isNull();
        assertThat(released.stringField("apr")).isNotBlank();
    }

    @Test
    void an_absent_field_is_left_alone_rather_than_released() {
        Household household = seedHousehold();
        UUID debt = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);
        long version = household.owner().get("/api/debts/" + debt).numberField("version");

        // A rename mentions nothing else. If absence meant release, this would silently turn the
        // household's own rate into one the app worked out.
        ApiResponse renamed =
                household
                        .owner()
                        .patch(
                                "/api/debts/" + debt,
                                """
                                {"name":"Visa 8823","version":%d}
                                """
                                        .formatted(version));

        assertThat(renamed.status()).isEqualTo(200);
        assertThat(renamed.stringField("name")).isEqualTo("Visa 8823");
        assertThat(renamed.stringField("apr")).isEqualTo("22.900");
        assertThat(renamed.stringField("aprSource")).isEqualTo("ENTERED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT apr::text FROM debts WHERE id = ?", String.class, debt))
                .isEqualTo("22.900");
    }

    @Test
    void entering_a_horizon_makes_the_payment_the_solved_field() {
        Household household = seedHousehold();
        UUID debt = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);
        long version = household.owner().get("/api/debts/" + debt).numberField("version");

        ApiResponse byDecember =
                household
                        .owner()
                        .patch(
                                "/api/debts/" + debt,
                                """
                                {"payoffMonth":"%s","version":%d}
                                """
                                        .formatted(
                                                YearMonth.now(ZoneOffset.UTC).plusMonths(23),
                                                version));

        assertThat(byDecember.status()).isEqualTo(200);
        assertThat(byDecember.stringField("paymentSource")).isEqualTo("SOLVED");
        assertThat(byDecember.stringField("horizonSource")).isEqualTo("ENTERED");
        // The required payment, not the plan's allocation.
        assertThat(byDecember.stringField("payment")).isNotEqualTo("118.0000");
    }

    @Test
    void a_minimum_that_never_clears_the_balance_is_accepted_and_says_why() {
        Household household = seedHousehold();

        // At 22.9% on $6,180 the monthly interest is $117.94, so $118 a month barely moves and
        // $100 never will. This is the common real case for a card, not an exotic one: a real
        // minimum is a percentage of the balance.
        ApiResponse created =
                household
                        .owner()
                        .post(
                                "/api/debts",
                                """
                                {"name":"Visa","kind":"CREDIT_CARD","balance":"6180.0000",
                                 "balanceDate":"%s","apr":"22.900","minimumPayment":"100.0000"}
                                """
                                        .formatted(TODAY));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.stringField("projectionReason")).isEqualTo("never-clears");
        assertThat(created.body())
                .contains(
                        "\"monthsLeft\":null",
                        "\"payoffMonth\":null",
                        "\"totalInterestLeft\":null");
    }

    @Test
    void a_debt_created_at_zero_is_refused_because_there_is_nothing_to_reduce() {
        Household household = seedHousehold();

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/debts",
                                """
                                {"name":"Settled","kind":"CREDIT_CARD","balance":"0.0000",
                                 "balanceDate":"%s","apr":"22.900","minimumPayment":"118.0000"}
                                """
                                        .formatted(TODAY));

        // Distinct from a debt that has REACHED zero, which is how one is paid off.
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.stringField("code")).isEqualTo("terms-unsolvable");
    }

    @Test
    void a_debt_with_no_rate_needs_a_payment_and_a_horizon_together() {
        Household household = seedHousehold();

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/debts",
                                """
                                {"name":"Nelnet","kind":"INSTALLMENT","balance":"20000.0000",
                                 "balanceDate":"%s","minimumPayment":"404.5800"}
                                """
                                        .formatted(TODAY));
        ApiResponse accepted =
                household
                        .owner()
                        .post(
                                "/api/debts",
                                """
                                {"name":"Nelnet","kind":"INSTALLMENT","balance":"20000.0000",
                                 "balanceDate":"%s","minimumPayment":"404.5800",
                                 "payment":"404.5800","monthsLeft":60}
                                """
                                        .formatted(TODAY));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("\"field\":\"apr\"");
        assertThat(accepted.status()).isEqualTo(201);
        // 20,000 over 60 months at 404.58 recovers 7.900% — the installment-loan case, where a
        // statement names everything except the rate.
        assertThat(accepted.stringField("aprSource")).isEqualTo("SOLVED");
        assertThat(accepted.stringField("apr")).startsWith("7.9");
    }

    @Test
    void both_faces_of_the_horizon_at_once_are_refused() {
        Household household = seedHousehold();

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/debts",
                                """
                                {"name":"Car loan","kind":"INSTALLMENT","balance":"10650.0000",
                                 "balanceDate":"%s","minimumPayment":"342.0000",
                                 "payment":"342.0000","monthsLeft":36,"payoffMonth":"2029-04"}
                                """
                                        .formatted(TODAY));

        // monthsLeft and payoffMonth are one unknown with two faces. Two stored answers to it can
        // disagree, which is why neither the row nor the request may hold both.
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("payoffMonth");
    }

    // ------------------------------------------------------------------------------- payments

    @Test
    void a_payment_moves_the_derived_balance_and_splits_exactly() {
        Household household = seedHousehold();
        UUID debt = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);

        ApiResponse logged =
                household
                        .owner()
                        .post(
                                "/api/debts/" + debt + "/payments",
                                """
                                {"paidDate":"%s","amount":"445.0000"}
                                """
                                        .formatted(TODAY));

        assertThat(logged.status()).isEqualTo(201);
        // round(6180 × 22.9 ÷ 1200, 4) = 117.9350, and the principal takes the remainder, so the
        // split reconciles to the cent by construction.
        assertThat(logged.stringField("interest")).isEqualTo("117.9350");
        assertThat(logged.stringField("principal")).isEqualTo("327.0650");
        assertThat(logged.stringField("balanceAfter")).isEqualTo("5852.9350");
        assertThat(logged.stringField("provenance")).isEqualTo("USER_LOGGED");

        // The balance is the anchor rolled forward by the payments after it — nothing stored it.
        assertThat(household.owner().get("/api/debts/" + debt).stringField("balance"))
                .isEqualTo("5852.9350");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Long.class))
                .as("a debt payment writes no ledger row (ADR-0035)")
                .isZero();
    }

    @Test
    void a_statement_correction_reconciles_the_balance_and_is_recorded_rather_than_applied() {
        Household household = seedHousehold();
        UUID debt = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);
        household
                .owner()
                .post(
                        "/api/debts/" + debt + "/payments",
                        """
                        {"paidDate":"%s","amount":"445.0000"}
                        """
                                .formatted(TODAY));

        ApiResponse confirmed =
                household
                        .owner()
                        .post(
                                "/api/debts/" + debt + "/balance",
                                """
                                {"balance":"5857.0350","balanceDate":"%s"}
                                """
                                        .formatted(TODAY));

        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(confirmed.stringField("balance")).isEqualTo("5857.0350");
        assertThat(confirmed.stringField("balanceSource")).isEqualTo("CONFIRMED");
        // "Statement corrected +$4.10": the gap is a ROW, not an edit. History is never silently
        // adjusted to agree with the present.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT amount::text FROM debt_payments"
                                        + " WHERE provenance = 'STATEMENT_CORRECTION'",
                                String.class))
                .isEqualTo("-4.1000");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM debt_payments WHERE debt_id = ?",
                                Long.class,
                                debt))
                .as("the original payment still stands beside the correction")
                .isEqualTo(2L);
    }

    @Test
    void deleting_the_linked_transaction_leaves_the_payment_and_does_not_move_the_balance() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        UUID charge = createTransaction(household.owner(), account, TODAY, "-445.0000", null);
        UUID debt = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);
        household
                .owner()
                .post(
                        "/api/debts/" + debt + "/payments",
                        """
                        {"paidDate":"%s","amount":"445.0000","transactionId":"%s"}
                        """
                                .formatted(TODAY, charge));

        assertThat(household.owner().delete("/api/transactions/" + charge).status()).isEqualTo(204);

        // The payment stands with its link cleared, its provenance unchanged, and the balance
        // exactly where it was (ADR-0035). The alternative — removing the payment with the
        // transaction — would mean tidying up a mistyped ledger row silently changed what the
        // household owes.
        ApiResponse payments = household.owner().get("/api/debts/" + debt + "/payments");
        assertThat(payments.numberField("totalElements")).isEqualTo(1L);
        assertThat(payments.body()).contains("\"transactionId\":null");
        assertThat(household.owner().get("/api/debts/" + debt).stringField("balance"))
                .isEqualTo("5852.9350");
    }

    @Test
    void a_payment_records_which_account_it_came_from_without_touching_that_account() {
        Household household = seedHousehold();
        UUID checking = createAccount(household.owner(), "Current", null);
        UUID debt = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);

        ApiResponse logged =
                household
                        .owner()
                        .post(
                                "/api/debts/" + debt + "/payments",
                                """
                                {"paidDate":"%s","amount":"445.0000","paidFromAccountId":"%s"}
                                """
                                        .formatted(TODAY, checking));

        assertThat(logged.status()).isEqualTo(201);
        assertThat(logged.stringField("paidFromAccountId")).isEqualTo(checking.toString());
        // "Paid from" is not a transaction (ADR-0035): the account has no rows and its balance is
        // its opening balance still. A debt screen that wrote to an account would be editing data
        // that belongs to a different part of the app.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Long.class)).isZero();
        assertThat(household.owner().get("/api/accounts/" + checking).stringField("balance"))
                .isEqualTo(
                        household
                                .owner()
                                .get("/api/accounts/" + checking)
                                .stringField("openingBalance"));
    }

    @Test
    void another_households_account_cannot_be_named_as_where_a_payment_came_from() {
        Household ours = seedHousehold();
        UUID debt = createDebt(ours.owner(), "Visa", "6180.00", "22.900", "118.00", null);
        UUID theirs = createAccount(ours.member(), "Current", null);
        // The member is in OUR household, so take an account that is genuinely elsewhere: an id
        // that resolves for nobody here. A foreign id in a body is a lookup key, never evidence.
        UUID foreign = UUID.randomUUID();

        ApiResponse refused =
                ours.owner()
                        .post(
                                "/api/debts/" + debt + "/payments",
                                """
                                {"paidDate":"%s","amount":"445.0000","paidFromAccountId":"%s"}
                                """
                                        .formatted(TODAY, foreign));

        // 404, not 403: a 403 would confirm the row exists somewhere.
        assertThat(refused.status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM debt_payments", Long.class)).isZero();
        assertThat(theirs).isNotNull();
    }

    @Test
    void deleting_the_account_a_payment_came_from_leaves_the_payment_standing() {
        Household household = seedHousehold();
        UUID checking = createAccount(household.owner(), "Current", null);
        UUID debt = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);
        household
                .owner()
                .post(
                        "/api/debts/" + debt + "/payments",
                        """
                        {"paidDate":"%s","amount":"445.0000","paidFromAccountId":"%s"}
                        """
                                .formatted(TODAY, checking));

        // The account is empty — no ledger rows were written — so it can be deleted. The FK is
        // ON DELETE SET NULL in the COLUMN-LIST form; the bare form would try to null
        // household_id and refuse this delete instead, which is the trap transactions.md hit on
        // category_id.
        assertThat(household.owner().delete("/api/accounts/" + checking).status()).isEqualTo(204);

        ApiResponse payments = household.owner().get("/api/debts/" + debt + "/payments");
        assertThat(payments.numberField("totalElements")).isEqualTo(1L);
        assertThat(payments.body()).contains("\"paidFromAccountId\":null");
        assertThat(household.owner().get("/api/debts/" + debt).stringField("balance"))
                .as("the balance is derived from the payment, not from the account")
                .isEqualTo("5852.9350");
    }

    @Test
    void correcting_where_a_payment_came_from_is_three_way() {
        Household household = seedHousehold();
        UUID checking = createAccount(household.owner(), "Current", null);
        UUID savings = createAccount(household.owner(), "Savings", null);
        UUID debt = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);
        ApiResponse logged =
                household
                        .owner()
                        .post(
                                "/api/debts/" + debt + "/payments",
                                """
                                {"paidDate":"%s","amount":"445.0000","paidFromAccountId":"%s"}
                                """
                                        .formatted(TODAY, checking));
        UUID payment = UUID.fromString(logged.stringField("id"));
        String path = "/api/debts/" + debt + "/payments/" + payment;

        // Not mentioned: left alone. A PATCH that omits a field must never be read as "clear it".
        ApiResponse untouched =
                household
                        .owner()
                        .patch(
                                path,
                                "{\"amount\":\"450.0000\",\"version\":"
                                        + logged.numberField("version")
                                        + "}");
        assertThat(untouched.status()).isEqualTo(200);
        assertThat(untouched.stringField("paidFromAccountId")).isEqualTo(checking.toString());

        // Named: moved.
        ApiResponse moved =
                household
                        .owner()
                        .patch(
                                path,
                                "{\"paidFromAccountId\":\""
                                        + savings
                                        + "\",\"version\":"
                                        + untouched.numberField("version")
                                        + "}");
        assertThat(moved.status()).isEqualTo(200);
        assertThat(moved.stringField("paidFromAccountId")).isEqualTo(savings.toString());

        // Explicitly null: cleared.
        ApiResponse cleared =
                household
                        .owner()
                        .patch(
                                path,
                                "{\"paidFromAccountId\":null,\"version\":"
                                        + moved.numberField("version")
                                        + "}");
        assertThat(cleared.status()).isEqualTo(200);
        assertThat(cleared.body()).contains("\"paidFromAccountId\":null");
    }

    @ParameterizedTest
    // A literal tab cannot appear unescaped inside a JSON string, so it is sent as an escape.
    // U+3000 is in the list because an ASCII-only blank check passes it.
    @ValueSource(strings = {" ", "   ", "\\t", "\u3000"})
    void a_blank_name_on_a_patch_is_refused_rather_than_failing(String blank) {
        Household household = seedHousehold();
        UUID debt = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);
        long version = household.owner().get("/api/debts/" + debt).numberField("version");

        ApiResponse refused =
                household
                        .owner()
                        .patch(
                                "/api/debts/" + debt,
                                "{\"name\":\"" + blank + "\",\"version\":" + version + "}");

        // @Size(max) alone passes whitespace, which then reaches Debt.rename, strips to empty and
        // throws IllegalArgumentException — a 500 with a stack trace at ERROR, from any
        // authenticated caller, in a loop, on a machine in somebody's house. Slice 3's audit
        // found exactly this on /api/accounts and this DTO reintroduced it. U+3000 is in the list
        // because an ASCII-only check passes it.
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.stringField("code")).isEqualTo("validation-failed");
        assertThat(household.owner().get("/api/debts/" + debt).stringField("name"))
                .isEqualTo("Visa");
    }

    @Test
    void a_payoff_month_beyond_what_a_projection_covers_is_refused_not_a_crash() {
        Household household = seedHousehold();

        // Far beyond the 600-month cap but an ordinary date: 422, naming the field.
        ApiResponse farOut =
                household
                        .owner()
                        .post(
                                "/api/debt-terms/solve",
                                """
                                {"balance":"1000.0000","currency":"GBP","apr":"10.000",
                                 "payoffMonth":"2200-01"}
                                """);

        assertThat(farOut.status()).isEqualTo(422);
        assertThat(farOut.stringField("code")).isEqualTo("terms-unsolvable");

        // A year wide enough to overflow the month arithmetic. YearMonth.parse accepts
        // "+999999999-12", so this reached Math.toIntExact inside Horizon.endingIn — an
        // ArithmeticException, which is not a DomainException, so it left as a 500 with a stack
        // trace. The deserializer turns out to refuse it first, which is why this asserts a
        // REFUSAL rather than a specific code: what matters is that it is answered, not thrown.
        // The guarantee itself now lives in Horizon.isSolvableSpan — see HorizonTest, which
        // exercises the overflow directly rather than through a parser that may or may not reach
        // it.
        ApiResponse absurd =
                household
                        .owner()
                        .post(
                                "/api/debt-terms/solve",
                                """
                                {"balance":"1000.0000","currency":"GBP","apr":"10.000",
                                 "payoffMonth":"+999999999-12"}
                                """);

        assertThat(absurd.status()).isIn(400, 422);
        assertThat(absurd.body()).doesNotContain("internal-error");

        // And the stored route, which must not land on a PostgreSQL date-range error and surface
        // as a 409 — also wrong, and unexplainable to a client.
        ApiResponse created =
                household
                        .owner()
                        .post(
                                "/api/debts",
                                """
                                {"name":"Visa","kind":"CREDIT_CARD","balance":"6180.00",
                                 "balanceDate":"%s","apr":"22.900","minimumPayment":"118.00",
                                 "payoffMonth":"2200-01"}
                                """
                                        .formatted(TODAY));

        assertThat(created.status()).isEqualTo(422);
        assertThat(created.stringField("code")).isEqualTo("terms-unsolvable");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM debts", Long.class)).isZero();
    }

    @Test
    void a_first_payment_month_outside_a_sane_window_is_refused_not_a_crash() {
        Household household = seedHousehold();

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/debt-terms/solve",
                                """
                                {"balance":"1000.0000","currency":"GBP","apr":"10.000",
                                 "payment":"100.0000","firstPaymentMonth":"4000-01"}
                                """);

        // The solver calls plusMonths on this, so an absurd value threw DateTimeException. A
        // century either side of now is more than any debt could mean.
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.stringField("code")).isEqualTo("terms-unsolvable");
    }

    @Test
    void a_transaction_cannot_settle_two_debt_payments() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        UUID charge = createTransaction(household.owner(), account, TODAY, "-445.0000", null);
        UUID visa = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);
        UUID card = createDebt(household.owner(), "Store card", "900.00", "29.900", "40.00", null);
        String body =
                """
                {"paidDate":"%s","amount":"445.0000","transactionId":"%s"}
                """
                        .formatted(TODAY, charge);

        assertThat(household.owner().post("/api/debts/" + visa + "/payments", body).status())
                .isEqualTo(201);
        ApiResponse second = household.owner().post("/api/debts/" + card + "/payments", body);

        // ADR-0031's argument about one transaction and one envelope entry. Counting one charge
        // against two debts would understate both balances plausibly.
        assertThat(second.status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM debt_payments", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void deleting_a_debt_takes_its_payments_and_no_transactions() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        UUID charge = createTransaction(household.owner(), account, TODAY, "-445.0000", null);
        UUID debt = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);
        household
                .owner()
                .post(
                        "/api/debts/" + debt + "/payments",
                        """
                        {"paidDate":"%s","amount":"445.0000","transactionId":"%s"}
                        """
                                .formatted(TODAY, charge));

        assertThat(household.owner().delete("/api/debts/" + debt).status()).isEqualTo(204);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM debt_payments", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Long.class))
                .as("a transaction outlives any plan it was filed under")
                .isEqualTo(1L);
    }

    @Test
    void a_payment_that_clears_the_balance_settles_the_debt_and_refuses_the_next_one() {
        Household household = seedHousehold();
        UUID debt = createDebt(household.owner(), "Store card", "100.00", "0.000", "40.00", null);

        ApiResponse cleared =
                household
                        .owner()
                        .post(
                                "/api/debts/" + debt + "/payments",
                                """
                                {"paidDate":"%s","amount":"120.0000"}
                                """
                                        .formatted(TODAY));
        ApiResponse again =
                household
                        .owner()
                        .post(
                                "/api/debts/" + debt + "/payments",
                                """
                                {"paidDate":"%s","amount":"10.0000"}
                                """
                                        .formatted(TODAY));

        assertThat(cleared.status()).isEqualTo(201);
        // Overpaying is not clamped: the screen says the debt is overpaid by that much, because
        // clamping would hide a real error in somebody's favour.
        assertThat(cleared.stringField("balanceAfter")).isEqualTo("-20.0000");
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.stringField("code")).isEqualTo("debt-paid-off");

        ApiResponse settled = household.owner().get("/api/debts/" + debt);
        assertThat(settled.body()).doesNotContain("\"paidOffAt\":null");
        assertThat(settled.body()).contains("\"rung\":null");
    }

    @Test
    void correcting_a_payment_keeps_the_interest_that_was_true_on_the_day() {
        Household household = seedHousehold();
        UUID debt = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);
        ApiResponse logged =
                household
                        .owner()
                        .post(
                                "/api/debts/" + debt + "/payments",
                                """
                                {"paidDate":"%s","amount":"445.0000"}
                                """
                                        .formatted(TODAY));
        UUID payment = UUID.fromString(logged.stringField("id"));

        ApiResponse corrected =
                household
                        .owner()
                        .patch(
                                "/api/debts/" + debt + "/payments/" + payment,
                                """
                                {"amount":"500.0000","version":%d}
                                """
                                        .formatted(logged.numberField("version")));

        assertThat(corrected.status()).isEqualTo(200);
        // The household is saying what they paid, not that the rate was different. Recomputing
        // the interest against today's APR would rewrite history to agree with the present.
        assertThat(corrected.stringField("interest")).isEqualTo("117.9350");
        assertThat(corrected.stringField("principal")).isEqualTo("382.0650");
        assertThat(corrected.stringField("balanceAfter")).isEqualTo("5797.9350");
    }

    @Test
    void deleting_a_payment_moves_the_balance_back() {
        Household household = seedHousehold();
        UUID debt = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);
        UUID payment =
                UUID.fromString(
                        household
                                .owner()
                                .post(
                                        "/api/debts/" + debt + "/payments",
                                        """
                                        {"paidDate":"%s","amount":"445.0000"}
                                        """
                                                .formatted(TODAY))
                                .stringField("id"));

        assertThat(household.owner().delete("/api/debts/" + debt + "/payments/" + payment).status())
                .isEqualTo(204);

        assertThat(household.owner().get("/api/debts/" + debt).stringField("balance"))
                .isEqualTo("6180.0000");
    }

    @Test
    void a_payment_dated_in_the_future_is_refused() {
        Household household = seedHousehold();
        UUID debt = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/debts/" + debt + "/payments",
                                """
                                {"paidDate":"%s","amount":"445.0000"}
                                """
                                        .formatted(LocalDate.now(ZoneOffset.UTC).plusDays(1)));

        // A payment somebody intends to make is not a payment: the balance would move for a month
        // that has not happened.
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("paidDate");
    }

    // ----------------------------------------------------------------------------------- plan

    @Test
    void the_plan_computes_both_strategies_whichever_one_is_selected() {
        Household household = seedHousehold();
        createDebt(household.owner(), "Visa", "6180.00", "22.900", "185.00", null);
        createDebt(household.owner(), "Car loan", "10650.00", "6.400", "342.00", null);
        createDebt(household.owner(), "Nelnet", "9650.00", "4.500", "118.00", null);
        household
                .owner()
                .put(
                        "/api/debt-plan",
                        """
                        {"strategy":"AVALANCHE","monthlyAmount":"905.0000","version":0}
                        """);

        ApiResponse plan = household.owner().get("/api/debt-plan");

        assertThat(plan.status()).isEqualTo(200);
        assertThat(plan.stringField("strategy")).isEqualTo("AVALANCHE");
        assertThat(plan.stringField("minimums")).isEqualTo("645.0000");
        // Both named strategies, always: the comparison card is a product requirement rather than
        // a view of the selection.
        assertThat(plan.body()).contains("\"avalanche\":", "\"snowball\":", "\"selected\":");
        assertThat(plan.body()).contains("\"rung\":1");
        assertThat(plan.numberField("debtCount")).isEqualTo(3L);
    }

    @Test
    void the_surplus_goes_to_rung_one_and_the_rest_get_their_minimum() {
        Household household = seedHousehold();
        createDebt(household.owner(), "Visa", "6180.00", "22.900", "185.00", null);
        createDebt(household.owner(), "Car loan", "10650.00", "6.400", "342.00", null);
        createDebt(household.owner(), "Nelnet", "9650.00", "4.500", "118.00", null);
        household
                .owner()
                .put(
                        "/api/debt-plan",
                        """
                        {"strategy":"AVALANCHE","monthlyAmount":"905.0000","version":0}
                        """);

        ApiResponse debts = household.owner().get("/api/debts");

        // "$185 minimum + $260 extra" on rung 1, and "minimum until the rung above clears" below
        // it. 905 − 645 = 260 of surplus, all of it on the highest rate.
        assertThat(debts.body()).contains("\"planExtra\":\"260.0000\"");
        assertThat(debts.body()).contains("\"planExtra\":\"0.0000\"");
        assertThat(debts.stringField("strategy")).isEqualTo("AVALANCHE");
        assertThat(debts.stringField("owed")).isEqualTo("26480.0000");
    }

    @Test
    void a_monthly_amount_below_the_minimums_is_accepted_and_produces_no_projection() {
        Household household = seedHousehold();
        createDebt(household.owner(), "Visa", "6180.00", "22.900", "185.00", null);
        createDebt(household.owner(), "Car loan", "10650.00", "6.400", "342.00", null);

        ApiResponse saved =
                household
                        .owner()
                        .put(
                                "/api/debt-plan",
                                """
                                {"strategy":"AVALANCHE","monthlyAmount":"100.0000","version":0}
                                """);

        // A household that genuinely cannot meet its minimums exists, and 422 is the wrong thing
        // to say to them. Every projection from such an amount would be a fiction.
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.body()).contains("\"belowMinimums\":true");
        assertThat(saved.body()).contains("\"reason\":\"plan-below-minimums\"");
        assertThat(saved.body()).contains("\"debtFreeMonth\":null");
    }

    @Test
    void the_what_if_writes_nothing() {
        Household household = seedHousehold();
        createDebt(household.owner(), "Visa", "6180.00", "22.900", "185.00", null);
        household
                .owner()
                .put(
                        "/api/debt-plan",
                        """
                        {"strategy":"AVALANCHE","monthlyAmount":"300.0000","version":0}
                        """);
        long snapshots =
                jdbc.queryForObject("SELECT count(*) FROM payoff_plan_snapshots", Long.class);

        ApiResponse whatIf =
                household.owner().get("/api/debt-plan/projection?monthlyAmount=600.0000");

        assertThat(whatIf.status()).isEqualTo(200);
        assertThat(whatIf.stringField("monthlyAmount")).isEqualTo("600.0000");
        assertThat(household.owner().get("/api/debt-plan").stringField("monthlyAmount"))
                .as("the stored plan is untouched by a what-if")
                .isEqualTo("300.0000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payoff_plan_snapshots", Long.class))
                .isEqualTo(snapshots);
    }

    @Test
    void changing_the_plan_takes_a_snapshot_so_next_month_has_something_to_compare_against() {
        Household household = seedHousehold();
        createDebt(household.owner(), "Visa", "6180.00", "22.900", "185.00", null);

        household
                .owner()
                .put(
                        "/api/debt-plan",
                        """
                        {"strategy":"AVALANCHE","monthlyAmount":"400.0000","version":0}
                        """);

        // A snapshot cannot be backfilled: a projection computed later from later debts is a
        // different answer. Slice 10 writes it; the screen that renders the delta is a later one.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payoff_plan_snapshots", Long.class))
                .isEqualTo(1L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT monthly_amount::text FROM payoff_plan_snapshots",
                                String.class))
                .isEqualTo("400.0000");
    }

    @Test
    void a_stale_plan_version_is_refused_rather_than_overwriting_the_other_members_choice() {
        Household household = seedHousehold();
        createDebt(household.owner(), "Visa", "6180.00", "22.900", "185.00", null);
        // The first PUT inserts the row, and an insert does not bump @Version — so both members
        // legitimately hold version 0 until somebody updates it.
        household
                .owner()
                .put(
                        "/api/debt-plan",
                        """
                        {"strategy":"AVALANCHE","monthlyAmount":"400.0000","version":0}
                        """);
        assertThat(
                        household
                                .owner()
                                .put(
                                        "/api/debt-plan",
                                        """
                                        {"monthlyAmount":"500.0000","version":0}
                                        """)
                                .numberField("version"))
                .isEqualTo(1L);

        ApiResponse collision =
                household
                        .member()
                        .put(
                                "/api/debt-plan",
                                """
                                {"strategy":"SNOWBALL","monthlyAmount":"400.0000","version":0}
                                """);

        assertThat(collision.status()).isEqualTo(409);
        assertThat(collision.stringField("code")).isEqualTo("stale-version");
    }

    @Test
    void a_custom_order_is_the_whole_order_and_names_only_this_households_debts() {
        Household household = seedHousehold();
        UUID visa = createDebt(household.owner(), "Visa", "6180.00", "22.900", "185.00", null);
        UUID car = createDebt(household.owner(), "Car loan", "10650.00", "6.400", "342.00", null);

        ApiResponse saved =
                household
                        .owner()
                        .put(
                                "/api/debt-plan",
                                """
                                {"strategy":"CUSTOM","monthlyAmount":"905.0000",
                                 "customOrder":["%s","%s"],"version":0}
                                """
                                        .formatted(car, visa));

        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.body()).contains("\"rung\":1,\"debtId\":\"" + car + "\"");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT custom_order FROM debts WHERE id = ?", Integer.class, car))
                .isZero();

        ApiResponse foreign =
                household
                        .owner()
                        .put(
                                "/api/debt-plan",
                                """
                                {"customOrder":["%s"],"version":%d}
                                """
                                        .formatted(
                                                UUID.randomUUID(),
                                                household
                                                        .owner()
                                                        .get("/api/debt-plan")
                                                        .numberField("version")));
        assertThat(foreign.status()).isEqualTo(404);
    }

    @Test
    void an_empty_plan_is_an_empty_state_rather_than_a_debt_free_date_of_today() {
        Household household = seedHousehold();

        ApiResponse plan = household.owner().get("/api/debt-plan");

        assertThat(plan.status()).isEqualTo(200);
        assertThat(plan.numberField("debtCount")).isZero();
        // A household with no debt being told it is debt-free THIS MONTH reads as a bug, not a
        // congratulation.
        assertThat(plan.body()).contains("\"currencies\":[]");
    }

    // ------------------------------------------------------------------------------ the solver

    @Test
    void the_solve_endpoint_fills_in_the_one_field_that_was_left_out() {
        Household household = seedHousehold();

        ApiResponse solved =
                household
                        .owner()
                        .post(
                                "/api/debt-terms/solve",
                                """
                                {"balance":"20000.0000","currency":"GBP","payment":"404.5800",
                                 "monthsLeft":60,"firstPaymentMonth":"2027-01"}
                                """);

        assertThat(solved.status()).isEqualTo(200);
        assertThat(solved.stringField("solvedField")).isEqualTo("apr");
        assertThat(solved.stringField("apr")).isEqualTo("7.900");
        // The bound on the search is a control, so the step count is reported: a control that is
        // not observable is a control nobody can test.
        assertThat(solved.numberField("bisectionSteps")).isPositive();
    }

    @Test
    void the_solve_endpoint_refuses_an_over_determined_set_and_an_under_determined_one() {
        Household household = seedHousehold();

        ApiResponse over =
                household
                        .owner()
                        .post(
                                "/api/debt-terms/solve",
                                """
                                {"balance":"20000.0000","currency":"GBP","apr":"7.900",
                                 "payment":"404.5800","monthsLeft":60}
                                """);
        ApiResponse under =
                household
                        .owner()
                        .post(
                                "/api/debt-terms/solve",
                                """
                                {"balance":"20000.0000","currency":"GBP","apr":"7.900"}
                                """);

        assertThat(over.status()).isEqualTo(422);
        assertThat(over.stringField("code")).isEqualTo("terms-over-determined");
        assertThat(under.status()).isEqualTo(400);
    }

    @Test
    void the_solve_endpoint_reads_no_stored_data_at_all() {
        Household household = seedHousehold();
        createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);

        ApiResponse solved =
                household
                        .owner()
                        .post(
                                "/api/debt-terms/solve",
                                """
                                {"balance":"1000.0000","currency":"GBP","apr":"0.000",
                                 "payment":"100.0000","firstPaymentMonth":"2027-01"}
                                """);

        // Arithmetic over a request body: 1,000 at 0% at 100 a month is ten months, whatever the
        // household's own debts say. It discloses nothing because it knows nothing.
        assertThat(solved.numberField("monthsLeft")).isEqualTo(10L);
        assertThat(solved.stringField("totalInterest")).isEqualTo("0.0000");
        assertThat(solved.stringField("payoffMonth")).isEqualTo("2027-10");
    }

    // ------------------------------------------------------------------- scoping and the roles

    /** Method, path and body for each route, with {@code {id}} to be substituted. */
    record Endpoint(String method, String path, String body) {

        ApiResponse callWith(ApiClient client, UUID debtId) {
            return client.send(method, path.replace("{id}", debtId.toString()), body);
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    /** The sixteen routes of the API table, with {@code {id}} to be substituted. */
    static List<Endpoint> everyRoute() {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        return List.of(
                new Endpoint("GET", "/api/debts", null),
                new Endpoint(
                        "POST",
                        "/api/debts",
                        """
                        {"name":"New","kind":"CREDIT_CARD","balance":"100.0000",
                         "balanceDate":"%s","apr":"1.000","minimumPayment":"10.0000"}
                        """
                                .formatted(today)),
                new Endpoint("GET", "/api/debts/{id}", null),
                new Endpoint("PATCH", "/api/debts/{id}", "{\"name\":\"Renamed\",\"version\":0}"),
                new Endpoint("DELETE", "/api/debts/{id}", null),
                new Endpoint("POST", "/api/debts/{id}/archive", null),
                new Endpoint("POST", "/api/debts/{id}/unarchive", null),
                new Endpoint("GET", "/api/debts/{id}/payments", null),
                new Endpoint(
                        "POST",
                        "/api/debts/{id}/payments",
                        "{\"paidDate\":\"" + today + "\",\"amount\":\"10.0000\"}"),
                new Endpoint(
                        "PATCH",
                        "/api/debts/{id}/payments/" + UUID.randomUUID(),
                        "{\"amount\":\"10.0000\",\"version\":0}"),
                new Endpoint("DELETE", "/api/debts/{id}/payments/" + UUID.randomUUID(), null),
                new Endpoint(
                        "POST",
                        "/api/debts/{id}/balance",
                        "{\"balance\":\"90.0000\",\"balanceDate\":\"" + today + "\"}"),
                new Endpoint("GET", "/api/debt-plan", null),
                new Endpoint("PUT", "/api/debt-plan", "{\"strategy\":\"SNOWBALL\",\"version\":0}"),
                new Endpoint("GET", "/api/debt-plan/projection", null),
                new Endpoint(
                        "POST",
                        "/api/debt-terms/solve",
                        """
                        {"balance":"1000.0000","currency":"GBP","apr":"0.000",
                         "payment":"100.0000"}
                        """));
    }

    /** The ones that change something. A {@code VIEWER} gets {@code 403} from every one. */
    static List<Endpoint> everyWrite() {
        return everyRoute().stream()
                .filter(endpoint -> !"GET".equals(endpoint.method()))
                // The solver changes nothing: it is arithmetic over a request body, and a VIEWER
                // may use it for the same reason it may read every figure.
                .filter(endpoint -> !endpoint.path().contains("debt-terms"))
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRoute")
    void an_unauthenticated_request_is_refused(Endpoint endpoint) {
        seedHousehold();

        // A browser that has fetched a CSRF token and nothing else, so the refusal is about
        // authentication rather than about the token: a session-less client sending no token is
        // turned away by the CSRF check first, which proves something else (see AdminIT).
        ApiResponse refused = endpoint.callWith(browser(), UUID.randomUUID());

        assertThat(refused.status()).isEqualTo(401);
        assertThat(refused.stringField("code")).isEqualTo("not-authenticated");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRoute")
    void an_anonymous_caller_with_no_csrf_token_learns_nothing_either(Endpoint endpoint) {
        seedHousehold();

        ApiResponse refused = endpoint.callWith(anonymous(), UUID.randomUUID());

        // 401 for a read and 403 csrf-token-required for a write, because that check runs first.
        // Either way the caller is turned away before anything reads a debt.
        assertThat(refused.status()).isIn(401, 403);
        // No figure of any kind. The instance path of a refusal can name a route — "/balance" —
        // but never a value, and the two are easy to confuse in an assertion.
        assertThat(refused.body()).doesNotContain("\"balance\":").doesNotContain("6180");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void a_viewer_may_read_everything_and_write_nothing(Endpoint endpoint) {
        Household household = seedHousehold();
        UUID debt = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);

        ApiResponse refused = endpoint.callWith(household.viewer(), debt);

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.stringField("code")).isEqualTo("read-only-role");
    }

    @Test
    void a_viewer_sees_the_plan_every_balance_and_the_whole_history() {
        Household household = seedHousehold();
        UUID debt = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);
        household
                .owner()
                .post(
                        "/api/debts/" + debt + "/payments",
                        """
                        {"paidDate":"%s","amount":"445.0000"}
                        """
                                .formatted(TODAY));

        assertThat(household.viewer().get("/api/debts").status()).isEqualTo(200);
        assertThat(household.viewer().get("/api/debts/" + debt).status()).isEqualTo(200);
        assertThat(
                        household
                                .viewer()
                                .get("/api/debts/" + debt + "/payments")
                                .numberField("totalElements"))
                .isEqualTo(1L);
        assertThat(household.viewer().get("/api/debt-plan").status()).isEqualTo(200);
        assertThat(household.viewer().get("/api/debt-plan/projection?monthlyAmount=600").status())
                .isEqualTo(200);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void a_write_without_a_csrf_token_is_refused_on_the_cookie_transport(Endpoint endpoint) {
        Household household = seedHousehold();
        UUID debt = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);

        ApiResponse refused =
                endpoint.callWith(signIn(OWNER_EMAIL, OWNER_SECRET).withoutCsrfToken(), debt);

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.stringField("code")).isEqualTo("csrf-token-required");
        assertThat(household.owner().get("/api/debts/" + debt).stringField("balance"))
                .isEqualTo("6180.0000");
    }

    @Test
    void the_bearer_transport_authorizes_identically() {
        Household household = seedHousehold();
        UUID debt = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);
        ApiClient phone = mobile(OWNER_EMAIL, OWNER_SECRET, "Ada's phone");
        ApiClient viewersPhone = mobile(VIEWER_EMAIL, VIEWER_SECRET, "Alan's phone");

        assertThat(phone.get("/api/debts/" + debt).status()).isEqualTo(200);
        assertThat(
                        phone.post(
                                        "/api/debts/" + debt + "/payments",
                                        """
                                        {"paidDate":"%s","amount":"445.0000"}
                                        """
                                                .formatted(TODAY))
                                .status())
                .as("CSRF does not apply to a bearer request; authorization still does")
                .isEqualTo(201);
        assertThat(
                        viewersPhone
                                .post(
                                        "/api/debts/" + debt + "/payments",
                                        """
                                        {"paidDate":"%s","amount":"445.0000"}
                                        """
                                                .formatted(TODAY))
                                .status())
                .as("neither transport may skip a check the other applies (ADR-0018)")
                .isEqualTo(403);
    }

    @Test
    void an_id_that_belongs_to_nothing_is_a_404_on_every_route_that_names_one() {
        Household household = seedHousehold();

        for (Endpoint endpoint : everyRoute()) {
            if (!endpoint.path().contains("{id}")) {
                continue;
            }
            assertThat(endpoint.callWith(household.owner(), UUID.randomUUID()).status())
                    .as("%s", endpoint)
                    .isEqualTo(404);
        }
    }

    @Test
    void a_stale_debt_version_is_refused_rather_than_overwriting_somebody_elses_edit() {
        Household household = seedHousehold();
        UUID debt = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);
        long stale = household.owner().get("/api/debts/" + debt).numberField("version");

        household
                .owner()
                .patch(
                        "/api/debts/" + debt,
                        """
                        {"name":"Visa 8823","version":%d}
                        """
                                .formatted(stale));
        ApiResponse second =
                household
                        .member()
                        .patch(
                                "/api/debts/" + debt,
                                """
                                {"name":"Chase","version":%d}
                                """
                                        .formatted(stale));

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.stringField("code")).isEqualTo("stale-version");
    }

    @Test
    void an_archived_debt_leaves_the_ladder_and_keeps_its_history() {
        Household household = seedHousehold();
        UUID debt = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);
        household
                .owner()
                .post(
                        "/api/debts/" + debt + "/payments",
                        """
                        {"paidDate":"%s","amount":"445.0000"}
                        """
                                .formatted(TODAY));

        assertThat(household.owner().post("/api/debts/" + debt + "/archive", null).status())
                .isEqualTo(200);

        assertThat(household.owner().get("/api/debts").body()).doesNotContain("Visa");
        assertThat(household.owner().get("/api/debts?includeArchived=true").body())
                .contains("Visa");
        assertThat(household.owner().get("/api/debt-plan").numberField("debtCount")).isZero();
        assertThat(
                        household
                                .owner()
                                .get("/api/debts/" + debt + "/payments")
                                .numberField("totalElements"))
                .as("an archived debt is retired, not erased")
                .isEqualTo(1L);
        assertThat(household.owner().post("/api/debts/" + debt + "/unarchive", null).status())
                .isEqualTo(200);
        assertThat(household.owner().get("/api/debt-plan").numberField("debtCount")).isEqualTo(1L);
    }

    @Test
    void progress_is_absent_when_there_is_no_original_balance() {
        Household household = seedHousehold();
        UUID without = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);
        UUID with =
                UUID.fromString(
                        household
                                .owner()
                                .post(
                                        "/api/debts",
                                        """
                                        {"name":"Car loan","kind":"INSTALLMENT",
                                         "balance":"6180.0000","balanceDate":"%s","apr":"6.400",
                                         "minimumPayment":"342.0000",
                                         "originalBalance":"10650.0000"}
                                        """
                                                .formatted(TODAY))
                                .stringField("id"));

        // Absent rather than 0%: a progress bar at zero on a revolving card is a claim nobody
        // made.
        assertThat(household.owner().get("/api/debts/" + without).body())
                .contains("\"progressPercent\":null", "\"paidOffSoFar\":null");
        ApiResponse baseline = household.owner().get("/api/debts/" + with);
        assertThat(baseline.stringField("paidOffSoFar")).isEqualTo("4470.0000");
        assertThat(baseline.stringField("progressPercent")).isEqualTo("42.0");
    }

    @Test
    void an_estimated_balance_assumes_the_minimums_and_the_interest_in_between() {
        Household household = seedHousehold();
        LocalDate threeMonthsAgo = LocalDate.now(ZoneOffset.UTC).minusMonths(3);
        UUID debt =
                UUID.fromString(
                        household
                                .owner()
                                .post(
                                        "/api/debts",
                                        """
                                        {"name":"Visa","kind":"CREDIT_CARD",
                                         "balance":"9254.0000","balanceDate":"%s","apr":"22.900",
                                         "minimumPayment":"118.0000"}
                                        """
                                                .formatted(threeMonthsAgo))
                                .stringField("id"));

        ApiResponse estimated = household.owner().get("/api/debts/" + debt);

        // The designs' own estimate screen shows 9,254 less three 118 payments as exactly 8,900,
        // which forgets three months of interest. The implementation follows the arithmetic: the
        // balance RISES, because 118 does not cover the interest on 9,254.
        assertThat(estimated.stringField("balanceSource")).isEqualTo("ESTIMATED");
        assertThat(estimated.numberField("estimatedMonths")).isEqualTo(3L);
        assertThat(estimated.stringField("balance")).isNotEqualTo("8900.0000");
        assertThat(new java.math.BigDecimal(estimated.stringField("balance")))
                .isGreaterThan(new java.math.BigDecimal("9254.0000"));
        assertThat(estimated.stringField("anchorBalance")).isEqualTo("9254.0000");
    }

    @Test
    void a_debt_cannot_be_held_at_an_account_in_another_currency() {
        Household household = seedHousehold();
        UUID euros =
                UUID.fromString(
                        household
                                .owner()
                                .post(
                                        "/api/accounts",
                                        """
                                        {"name":"Euro card","type":"CREDIT_CARD",
                                         "currency":"EUR","openingBalance":"0.0000"}
                                        """)
                                .stringField("id"));

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/debts",
                                """
                                {"name":"Visa","kind":"CREDIT_CARD","balance":"6180.0000",
                                 "balanceDate":"%s","apr":"22.900","minimumPayment":"118.0000",
                                 "currency":"GBP","accountId":"%s"}
                                """
                                        .formatted(TODAY, euros));
        ApiResponse accepted =
                household
                        .owner()
                        .post(
                                "/api/debts",
                                """
                                {"name":"Visa","kind":"CREDIT_CARD","balance":"6180.0000",
                                 "balanceDate":"%s","apr":"22.900","minimumPayment":"118.0000",
                                 "accountId":"%s"}
                                """
                                        .formatted(TODAY, euros));

        // The account owns the currency of anything held at it, exactly as it does for a
        // transaction. Asking for a different one is refused rather than ignored.
        assertThat(refused.status()).isEqualTo(400);
        assertThat(accepted.status()).isEqualTo(201);
        assertThat(accepted.stringField("currency")).isEqualTo("EUR");
    }
}
