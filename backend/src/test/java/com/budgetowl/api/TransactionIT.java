package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Transactions over real HTTP against real PostgreSQL — the slice that makes the app usable.
 *
 * <p>The cases chosen are the ones that would cost a user money or data if they were wrong: that an
 * amount survives the wire as a string rather than a rounded double, that a {@code PATCH} of one
 * field does not clear the others, and that recording a transaction is never blocked on tidying up
 * first.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TransactionIT extends ApiTestBase {

    @Test
    void recording_a_transaction_changes_the_accounts_balance() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", "4321");

        assertThat(household.owner().get("/api/accounts/" + account).stringField("balance"))
                .as("the opening balance, before anything is recorded")
                .isEqualTo("100.0000");

        createTransaction(
                household.owner(),
                account,
                "2026-11-01",
                "-30.5000",
                seededCategoryId("Groceries"));

        ApiResponse after = household.owner().get("/api/accounts/" + account);
        assertThat(after.stringField("balance"))
                .as("balance is derived: opening plus the sum of the transactions")
                .isEqualTo("69.5000");
        assertThat(after.stringField("openingBalance"))
                .as("the opening balance is a different field and must not move")
                .isEqualTo("100.0000");
    }

    @Test
    void an_amount_crosses_the_wire_as_a_string_and_keeps_every_digit() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);

        ApiResponse created =
                household
                        .owner()
                        .post(
                                "/api/transactions",
                                """
                                {"accountId":"%s","date":"2026-11-04","amount":"-1234.5678"}
                                """
                                        .formatted(account));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body())
                .as("a JSON number would be read as a double by most clients (ADR-0006)")
                .contains("\"amount\":\"-1234.5678\"");
        assertThat(created.stringField("amount")).isEqualTo("-1234.5678");
    }

    @Test
    void an_amount_sent_as_a_json_number_is_refused_rather_than_silently_rounded() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/transactions",
                                """
                                {"accountId":"%s","date":"2026-11-04","amount":-1234.5678}
                                """
                                        .formatted(account));

        assertThat(refused.status())
                .as("by the time it arrives as a number, the sender's double already rounded it")
                .isEqualTo(400);
    }

    @Test
    void a_zero_amount_is_refused_because_it_moves_no_money() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/transactions",
                                """
                                {"accountId":"%s","date":"2026-11-04","amount":"0.0000"}
                                """
                                        .formatted(account));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("amount");
    }

    @Test
    void an_amount_with_more_than_four_decimal_places_is_refused_not_rounded() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/transactions",
                                """
                                {"accountId":"%s","date":"2026-11-04","amount":"-1.234567"}
                                """
                                        .formatted(account));

        assertThat(refused.status())
                .as("rounding somebody's money without telling them is the ADR-0006 defect")
                .isEqualTo(400);
    }

    @Test
    void a_transaction_needs_no_category_and_says_so() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);

        ApiResponse created =
                household
                        .owner()
                        .post(
                                "/api/transactions",
                                """
                                {"accountId":"%s","date":"2026-11-05","amount":"-9.9900"}
                                """
                                        .formatted(account));

        assertThat(created.status())
                .as("recording what happened must never be gated on tidying up first")
                .isEqualTo(201);
        assertThat(created.body()).contains("\"categoryId\":null");
    }

    @Test
    void the_uncategorised_filter_is_the_work_queue_and_is_not_the_same_as_no_category_filter() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        createTransaction(household.owner(), account, "2026-11-06", "-5.0000", null);
        createTransaction(
                household.owner(), account, "2026-11-07", "-6.0000", seededCategoryId("Groceries"));

        assertThat(household.owner().get("/api/transactions").numberField("totalElements"))
                .as("no category filter means every category, including none")
                .isEqualTo(2);
        assertThat(
                        household
                                .owner()
                                .get("/api/transactions?uncategorised=true")
                                .numberField("totalElements"))
                .isEqualTo(1);
    }

    @Test
    void a_caller_cannot_declare_a_transaction_reconciled() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/transactions",
                                """
                                {"accountId":"%s","date":"2026-11-20","amount":"-5.0000",
                                 "status":"RECONCILED"}
                                """
                                        .formatted(account));

        // RECONCILED asserts that a statement period was closed against this row. That is
        // reconciliation's to say (slice 13), not a user's, so the contract cannot express it.
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("status");
    }

    @Test
    void the_work_queue_narrows_with_the_other_filters_rather_than_replacing_them() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", null);
        UUID savings = createAccount(household.owner(), "Savings", null);
        createTransaction(household.owner(), current, "2026-05-01", "-5.0000", null);
        createTransaction(household.owner(), savings, "2026-05-02", "-6.0000", null);
        createTransaction(
                household.owner(), current, "2026-05-03", "-7.0000", seededCategoryId("Groceries"));

        // Both filters at once is one question — "what still needs a category on this account" —
        // and must not quietly answer a different one.
        assertThat(
                        household
                                .owner()
                                .get("/api/transactions?uncategorised=true&accountId=" + current)
                                .numberField("totalElements"))
                .isEqualTo(1);
        assertThat(
                        household
                                .owner()
                                .get("/api/transactions?uncategorised=true&from=2026-05-02")
                                .numberField("totalElements"))
                .isEqualTo(1);
    }

    @Test
    void a_payee_is_created_on_first_use_and_reused_case_insensitively_afterwards() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);

        ApiResponse first =
                household
                        .owner()
                        .post(
                                "/api/transactions",
                                """
                                {"accountId":"%s","date":"2026-11-08","amount":"-12.0000",
                                 "payeeName":"Tesco"}
                                """
                                        .formatted(account));
        ApiResponse second =
                household
                        .owner()
                        .post(
                                "/api/transactions",
                                """
                                {"accountId":"%s","date":"2026-11-09","amount":"-13.0000",
                                 "payeeName":"tesco"}
                                """
                                        .formatted(account));

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.stringField("payeeId"))
                .as("'tesco' after 'Tesco' must reconcile to one payee, not two")
                .isEqualTo(first.stringField("payeeId"));
        assertThat(second.stringField("payeeName"))
                .as("and keeps the name as it was first typed")
                .isEqualTo("Tesco");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payees", Long.class)).isEqualTo(1);
    }

    @Test
    void payee_suggestions_match_a_prefix_and_treat_a_percent_sign_as_a_literal() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        household
                .owner()
                .post(
                        "/api/transactions",
                        """
                        {"accountId":"%s","date":"2026-11-10","amount":"-20.0000",
                         "payeeName":"100%% Chiropractic"}
                        """
                                .formatted(account));
        household
                .owner()
                .post(
                        "/api/transactions",
                        """
                        {"accountId":"%s","date":"2026-11-10","amount":"-21.0000",
                         "payeeName":"Waitrose"}
                        """
                                .formatted(account));

        assertThat(household.owner().get("/api/transactions/payee-suggestions?typed=Wait").body())
                .contains("Waitrose")
                .doesNotContain("Chiropractic");
        assertThat(household.owner().get("/api/transactions/payee-suggestions?typed=100%25").body())
                .as("an unescaped %% would be a wildcard and match every payee")
                .contains("Chiropractic")
                .doesNotContain("Waitrose");
    }

    @Test
    void an_empty_search_suggests_nothing_rather_than_the_whole_payee_list() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        household
                .owner()
                .post(
                        "/api/transactions",
                        """
                        {"accountId":"%s","date":"2026-11-10","amount":"-20.0000",
                         "payeeName":"Waitrose"}
                        """
                                .formatted(account));

        // Returning every payee to a form that has been clicked into but not typed in would leak
        // more than the question asked for, and suggest nothing anybody could use.
        assertThat(household.owner().get("/api/transactions/payee-suggestions?typed=").body())
                .isEqualTo("[]");
        assertThat(household.owner().get("/api/transactions/payee-suggestions?typed=%20").body())
                .isEqualTo("[]");
    }

    @Test
    void patching_one_field_leaves_the_others_alone() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        ApiResponse created =
                household
                        .owner()
                        .post(
                                "/api/transactions",
                                """
                                {"accountId":"%s","date":"2026-11-11","amount":"-40.0000",
                                 "payeeName":"Boots","categoryId":"%s","note":"flu jab"}
                                """
                                        .formatted(account, seededCategoryId("Health")));

        ApiResponse patched =
                household
                        .owner()
                        .patch(
                                "/api/transactions/" + created.stringField("id"),
                                """
                                {"amount":"-45.0000","version":%d}
                                """
                                        .formatted(created.numberField("version")));

        assertThat(patched.status()).isEqualTo(200);
        assertThat(patched.stringField("amount")).isEqualTo("-45.0000");
        assertThat(patched.stringField("payeeName"))
                .as("an absent payeeName must not clear the payee")
                .isEqualTo("Boots");
        assertThat(patched.stringField("note"))
                .as("an absent note must not clear the note")
                .isEqualTo("flu jab");
        assertThat(patched.stringField("categoryName")).isEqualTo("Health");
    }

    @Test
    void an_explicit_null_clears_a_field_that_an_absent_key_would_have_left_alone() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        ApiResponse created =
                household
                        .owner()
                        .post(
                                "/api/transactions",
                                """
                                {"accountId":"%s","date":"2026-11-12","amount":"-40.0000",
                                 "payeeName":"Boots","categoryId":"%s","note":"flu jab"}
                                """
                                        .formatted(account, seededCategoryId("Health")));

        ApiResponse cleared =
                household
                        .owner()
                        .patch(
                                "/api/transactions/" + created.stringField("id"),
                                """
                                {"payeeName":null,"categoryId":null,"note":null,"version":%d}
                                """
                                        .formatted(created.numberField("version")));

        assertThat(cleared.status()).isEqualTo(200);
        assertThat(cleared.body())
                .contains("\"payeeId\":null")
                .contains("\"categoryId\":null")
                .contains("\"note\":null");
    }

    @Test
    void an_archived_account_takes_no_new_transactions() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        household.owner().post("/api/accounts/" + account + "/archive", null);

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/transactions",
                                """
                                {"accountId":"%s","date":"2026-11-13","amount":"-1.0000"}
                                """
                                        .formatted(account));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body()).contains("account-archived");
    }

    @Test
    void an_account_with_transactions_can_no_longer_be_deleted_or_re_denominated() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        createTransaction(household.owner(), account, "2026-11-14", "-7.0000", null);
        long version = household.owner().get("/api/accounts/" + account).numberField("version");

        ApiResponse deletion = household.owner().delete("/api/accounts/" + account);
        assertThat(deletion.status())
                .as("the slice-3 rule, now with a real table behind it")
                .isEqualTo(409);
        assertThat(deletion.body()).contains("account-not-empty");

        ApiResponse redenomination =
                household
                        .owner()
                        .patch(
                                "/api/accounts/" + account,
                                """
                                {"currency":"EUR","version":%d}
                                """
                                        .formatted(version));
        assertThat(redenomination.status()).isEqualTo(409);
        assertThat(redenomination.body()).contains("account-currency-immutable");
    }

    @Test
    void an_account_whose_transactions_net_to_zero_still_has_history() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        createTransaction(household.owner(), account, "2026-11-15", "-50.0000", null);
        createTransaction(household.owner(), account, "2026-11-15", "50.0000", null);

        ApiResponse read = household.owner().get("/api/accounts/" + account);

        assertThat(read.stringField("balance")).isEqualTo("100.0000");
        assertThat(read.body())
                .as("a total of zero is not the same fact as no transactions")
                .contains("\"empty\":false");
        assertThat(household.owner().delete("/api/accounts/" + account).status()).isEqualTo(409);
    }

    @Test
    void the_ledger_filters_by_account_category_date_and_text() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", null);
        UUID savings = createAccount(household.owner(), "Savings", null);
        UUID groceries = seededCategoryId("Groceries");

        household
                .owner()
                .post(
                        "/api/transactions",
                        """
                        {"accountId":"%s","date":"2026-01-10","amount":"-11.0000",
                         "categoryId":"%s","payeeName":"Lidl","note":"weekly shop"}
                        """
                                .formatted(current, groceries));
        createTransaction(household.owner(), current, "2026-06-10", "-12.0000", null);
        createTransaction(household.owner(), savings, "2026-06-11", "-13.0000", null);

        assertThat(
                        household
                                .owner()
                                .get("/api/transactions?accountId=" + savings)
                                .numberField("totalElements"))
                .isEqualTo(1);
        assertThat(
                        household
                                .owner()
                                .get("/api/transactions?categoryId=" + groceries)
                                .numberField("totalElements"))
                .isEqualTo(1);
        assertThat(
                        household
                                .owner()
                                .get("/api/transactions?from=2026-06-01&to=2026-06-30")
                                .numberField("totalElements"))
                .isEqualTo(2);
        assertThat(
                        household
                                .owner()
                                .get("/api/transactions?search=weekly")
                                .numberField("totalElements"))
                .as("the note is searched")
                .isEqualTo(1);
        assertThat(
                        household
                                .owner()
                                .get("/api/transactions?search=lidl")
                                .numberField("totalElements"))
                .as("and so is the payee's name, case-insensitively")
                .isEqualTo(1);
    }

    @Test
    void the_ledger_is_newest_first() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        createTransaction(household.owner(), account, "2026-03-01", "-1.0000", null);
        createTransaction(household.owner(), account, "2026-09-01", "-2.0000", null);

        String body = household.owner().get("/api/transactions").body();

        assertThat(body.indexOf("2026-09-01"))
                .as("a ledger reads from the most recent downwards")
                .isLessThan(body.indexOf("2026-03-01"));
    }

    @Test
    void a_single_currency_page_carries_its_total_and_a_mixed_one_does_not() {
        Household household = seedHousehold();
        UUID sterling = createAccount(household.owner(), "Current", null);
        createTransaction(household.owner(), sterling, "2026-11-16", "-10.0000", null);
        createTransaction(household.owner(), sterling, "2026-11-17", "-15.0000", null);

        ApiResponse single = household.owner().get("/api/transactions");
        assertThat(single.stringField("pageTotal")).isEqualTo("-25.0000");
        assertThat(single.stringField("pageTotalCurrency")).isEqualTo("GBP");

        ApiResponse euro =
                household
                        .owner()
                        .post(
                                "/api/accounts",
                                """
                                {"name":"Euro","type":"SAVINGS","currency":"EUR",
                                 "openingBalance":"0.0000"}
                                """);
        createTransaction(
                household.owner(),
                UUID.fromString(euro.stringField("id")),
                "2026-11-18",
                "-20.0000",
                null);

        ApiResponse mixed = household.owner().get("/api/transactions");
        assertThat(mixed.body())
                .as("a cross-currency total would be a number nobody could check (ADR-0022)")
                .contains("\"pageTotal\":null");
    }

    @Test
    void a_transaction_cannot_be_recorded_against_another_households_account() {
        Household household = seedHousehold();

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/transactions",
                                """
                                {"accountId":"%s","date":"2026-11-19","amount":"-1.0000"}
                                """
                                        .formatted(UUID.randomUUID()));

        assertThat(refused.status())
                .as("an id in a body is a lookup key and never evidence of ownership")
                .isEqualTo(404);
    }

    @Test
    void a_category_from_outside_the_household_cannot_be_filed_against() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/transactions",
                                """
                                {"accountId":"%s","date":"2026-11-19","amount":"-1.0000",
                                 "categoryId":"%s"}
                                """
                                        .formatted(account, UUID.randomUUID()));

        assertThat(refused.status()).isEqualTo(404);
    }

    /** Method, path and body for each of the five routes, with {@code {id}} to be substituted. */
    static List<Endpoint> everyRoute() {
        return List.of(
                new Endpoint("GET", "/api/transactions", null),
                new Endpoint(
                        "POST",
                        "/api/transactions",
                        "{\"accountId\":\"{id}\",\"date\":\"2026-11-20\",\"amount\":\"-1.00\"}"),
                new Endpoint("GET", "/api/transactions/{id}", null),
                new Endpoint(
                        "PATCH", "/api/transactions/{id}", "{\"version\":0,\"amount\":\"-2.00\"}"),
                new Endpoint("DELETE", "/api/transactions/{id}", null));
    }

    /** The three that change something. A {@code VIEWER} gets {@code 403} from every one. */
    static List<Endpoint> everyWrite() {
        return everyRoute().stream().filter(endpoint -> !"GET".equals(endpoint.method())).toList();
    }

    /**
     * The highest-value authorization test in this slice, and the one that matters most in the
     * whole codebase: this is the table the money is in.
     *
     * <p>Parameterized over every write rather than written once, because the role check is one
     * line per method and the one that gets forgotten is the one nobody listed.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void answers403ToAViewerOnEveryWrite(Endpoint endpoint) {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        UUID transaction =
                createTransaction(household.owner(), account, "2026-11-20", "-3.0000", null);

        // The POST substitutes an account id and the rest a transaction id, so each route is
        // refused for the role rather than for a 404 it would have got anyway.
        ApiResponse response =
                endpoint.callWith(
                        household.viewer(),
                        "POST".equals(endpoint.method()) ? account : transaction);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.stringField("code")).isEqualTo("read-only-role");
    }

    @Test
    void a_viewer_may_read_the_ledger() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        createTransaction(household.owner(), account, "2026-11-20", "-3.0000", null);

        assertThat(household.viewer().get("/api/transactions").status()).isEqualTo(200);
        assertThat(household.viewer().get("/api/transactions").body()).contains("Current");
    }

    /** Method, path and body for one route, with {@code {id}} to be substituted. */
    record Endpoint(String method, String path, String body) {

        ApiResponse callWith(ApiClient client, UUID id) {
            return client.send(
                    method,
                    path.replace("{id}", id.toString()),
                    body == null ? null : body.replace("{id}", id.toString()));
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    @Test
    void a_member_may_record_and_delete_a_transaction() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);

        UUID transaction =
                createTransaction(household.member(), account, "2026-11-20", "-8.0000", null);

        assertThat(household.member().delete("/api/transactions/" + transaction).status())
                .isEqualTo(204);
        assertThat(household.owner().get("/api/accounts/" + account).stringField("balance"))
                .as("deleting a transaction puts the balance back")
                .isEqualTo("100.0000");
    }

    @Test
    void a_stale_version_is_refused_rather_than_overwriting_somebody_elses_correction() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        UUID transaction =
                createTransaction(household.owner(), account, "2026-11-20", "-9.0000", null);
        long stale =
                household.owner().get("/api/transactions/" + transaction).numberField("version");

        household
                .owner()
                .patch(
                        "/api/transactions/" + transaction,
                        """
                        {"amount":"-10.0000","version":%d}
                        """
                                .formatted(stale));

        ApiResponse second =
                household
                        .member()
                        .patch(
                                "/api/transactions/" + transaction,
                                """
                                {"amount":"-11.0000","version":%d}
                                """
                                        .formatted(stale));

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.body()).contains("stale-version");
    }

    @Test
    void a_transaction_defaults_to_cleared_because_the_person_typing_it_watched_it_happen() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);

        ApiResponse created =
                household
                        .owner()
                        .post(
                                "/api/transactions",
                                """
                                {"accountId":"%s","date":"2026-11-20","amount":"-2.0000"}
                                """
                                        .formatted(account));

        assertThat(created.stringField("status")).isEqualTo("CLEARED");
    }
}
