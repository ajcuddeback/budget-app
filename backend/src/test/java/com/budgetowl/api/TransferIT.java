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
 * Transfers over real HTTP against real PostgreSQL.
 *
 * <p>The cases chosen are the ones that would make the feature wrong rather than merely missing:
 * that the pair is written atomically and both balances move, that neither leg can be touched on
 * its own, that a cross-currency pair keeps <b>both</b> figures rather than inventing a rate, and
 * that a transfer is never income, spending or categorisable.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TransferIT extends ApiTestBase {

    private static final String DATE = "2026-08-12";

    @Test
    void a_transfer_writes_two_rows_and_moves_both_balances() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "4321");
        UUID savings = createAccount(household.owner(), "Savings", "8765");

        ApiResponse created = transfer(household.owner(), current, savings, "250.00");

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.stringField("sent")).isEqualTo("250.0000");
        assertThat(created.stringField("received")).isEqualTo("250.0000");
        assertThat(created.body())
                .contains("\"crossCurrency\":false")
                .contains("\"impliedRate\":null");
        assertThat(created.stringField("fromAccountName")).isEqualTo("Current");
        assertThat(created.stringField("toAccountName")).isEqualTo("Savings");

        // Two rows, not one. An account's balance is the sum of its own transactions, and that
        // stays true only if every movement is a row in the account it moved.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class))
                .isEqualTo(2);
        // Both accounts open at 100.00 (ApiTestBase), so the figures below are that plus each
        // account's own leg — which is the invariant worth asserting: balance is opening balance
        // plus this account's transactions, in both directions.
        assertThat(household.owner().get("/api/accounts/" + current).stringField("balance"))
                .isEqualTo("-150.0000");
        assertThat(household.owner().get("/api/accounts/" + savings).stringField("balance"))
                .isEqualTo("350.0000");
    }

    @Test
    void both_legs_appear_in_the_ledger_marked_as_a_transfer() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "4321");
        UUID savings = createAccount(household.owner(), "Savings", "8765");
        transfer(household.owner(), current, savings, "250.00");

        ApiResponse ledger = household.owner().get("/api/transactions");

        // An account whose balance changed with nothing to show for it is a bug report.
        assertThat(ledger.numberField("totalElements")).isEqualTo(2);
        assertThat(ledger.body()).contains("\"transfer\":true");
    }

    @Test
    void a_transfer_is_neither_income_nor_spending_and_cannot_be_filed() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "4321");
        UUID savings = createAccount(household.owner(), "Savings", "8765");
        transfer(household.owner(), current, savings, "250.00");

        // Not in the queue of things waiting for a category. Moving your own money is not
        // spending, and counting it would say you spent your savings.
        ApiResponse uncategorised = household.owner().get("/api/transactions?uncategorised=true");
        assertThat(uncategorised.numberField("totalElements")).isZero();

        // And the request the feature doc says is refused, actually refused. Asserting over SQL
        // that no transfer row carries a category proves nothing: no request shape can set one,
        // so that query returns zero whatever the code does. A security review caught it.
        assertThat(
                        household
                                .owner()
                                .post(
                                        "/api/transfers",
                                        """
                                        {"fromAccountId":"%s","toAccountId":"%s","date":"%s",
                                         "amount":"1.00","categoryId":"%s"}
                                        """
                                                .formatted(
                                                        current,
                                                        savings,
                                                        DATE,
                                                        seededCategoryId("Groceries")))
                                .status())
                .isEqualTo(400);
        assertThat(
                        household
                                .owner()
                                .post(
                                        "/api/transfers",
                                        """
                                        {"fromAccountId":"%s","toAccountId":"%s","date":"%s",
                                         "amount":"1.00","payeeName":"Someone"}
                                        """
                                                .formatted(current, savings, DATE))
                                .status())
                .isEqualTo(400);
    }

    @Test
    void a_cross_currency_transfer_keeps_both_figures_and_invents_no_rate() {
        Household household = seedHousehold();
        UUID pounds = createAccount(household.owner(), "Current", "4321");
        UUID euros = createAccountIn(household.owner(), "Holiday", "EUR");

        ApiResponse created =
                household
                        .owner()
                        .post(
                                "/api/transfers",
                                """
                                {"fromAccountId":"%s","toAccountId":"%s","date":"%s",
                                 "amount":"200.00","amountReceived":"228.40"}
                                """
                                        .formatted(pounds, euros, DATE));

        // £200 leaving does not make €200 arrive, and only the statement knows the second figure.
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.stringField("sent")).isEqualTo("200.0000");
        assertThat(created.stringField("sentCurrency")).isEqualTo("GBP");
        assertThat(created.stringField("received")).isEqualTo("228.4000");
        assertThat(created.stringField("receivedCurrency")).isEqualTo("EUR");
        assertThat(created.body()).contains("\"crossCurrency\":true");
        // Derived for display, stored nowhere (ADR-0022).
        assertThat(created.stringField("impliedRate")).isEqualTo("1.142000");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM information_schema.columns"
                                        + " WHERE table_name = 'transactions'"
                                        + " AND column_name LIKE '%rate%'",
                                Integer.class))
                .as("no rate is persisted anywhere")
                .isZero();
    }

    @Test
    void a_cross_currency_transfer_needs_the_second_figure() {
        Household household = seedHousehold();
        UUID pounds = createAccount(household.owner(), "Current", "4321");
        UUID euros = createAccountIn(household.owner(), "Holiday", "EUR");

        ApiResponse refused = transfer(household.owner(), pounds, euros, "200.00");

        // Guessing would put a number in somebody's ledger that no bank ever agreed to.
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("amountReceived");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class))
                .isZero();
    }

    @Test
    void a_second_figure_is_refused_when_both_accounts_share_a_currency() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "4321");
        UUID savings = createAccount(household.owner(), "Savings", "8765");

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/transfers",
                                """
                                {"fromAccountId":"%s","toAccountId":"%s","date":"%s",
                                 "amount":"250.00","amountReceived":"240.00"}
                                """
                                        .formatted(current, savings, DATE));

        // Refused rather than ignored: a figure that silently did nothing is a client believing
        // it recorded something it did not, and two numbers that can disagree with no tie-break.
        assertThat(refused.status()).isEqualTo(400);
    }

    @Test
    void a_transfer_to_the_same_account_is_refused() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "4321");

        ApiResponse refused = transfer(household.owner(), current, current, "250.00");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class))
                .isZero();
    }

    @Test
    void an_archived_account_on_either_side_is_refused() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "4321");
        UUID savings = createAccount(household.owner(), "Savings", "8765");
        assertThat(household.owner().post("/api/accounts/" + savings + "/archive", "").status())
                .isEqualTo(200);

        assertThat(transfer(household.owner(), current, savings, "10.00").status()).isEqualTo(409);
        assertThat(transfer(household.owner(), savings, current, "10.00").status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class))
                .isZero();
    }

    @Test
    void neither_leg_can_be_edited_or_deleted_on_its_own() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "4321");
        UUID savings = createAccount(household.owner(), "Savings", "8765");
        transfer(household.owner(), current, savings, "250.00");
        List<UUID> legs = legIds();

        for (UUID leg : legs) {
            ApiResponse patched =
                    household
                            .owner()
                            .patch(
                                    "/api/transactions/" + leg,
                                    "{\"version\":0,\"amount\":\"-1.00\"}");
            // Editing one leg's amount would leave a same-currency pair that no longer agrees,
            // and the pair trigger deliberately does not compare amounts.
            assertThat(patched.status()).isEqualTo(409);
            assertThat(patched.body()).contains("transfer-leg-not-separate");
            assertThat(household.owner().delete("/api/transactions/" + leg).status())
                    .isEqualTo(409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void editing_a_transfer_rewrites_both_legs_at_once() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "4321");
        UUID savings = createAccount(household.owner(), "Savings", "8765");
        ApiResponse created = transfer(household.owner(), current, savings, "250.00");
        UUID id = UUID.fromString(created.stringField("id"));

        ApiResponse edited =
                household
                        .owner()
                        .patch(
                                "/api/transfers/" + id,
                                """
                                {"amount":"300.00","date":"2026-08-20","note":"moved more",
                                 "version":%d}
                                """
                                        .formatted(created.numberField("version")));

        assertThat(edited.status()).isEqualTo(200);
        assertThat(edited.stringField("sent")).isEqualTo("300.0000");
        assertThat(edited.stringField("received")).isEqualTo("300.0000");
        // Both legs, or the pair would no longer agree — and no later sum would notice.
        assertThat(household.owner().get("/api/accounts/" + current).stringField("balance"))
                .isEqualTo("-200.0000");
        assertThat(household.owner().get("/api/accounts/" + savings).stringField("balance"))
                .isEqualTo("400.0000");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(DISTINCT date) FROM transactions", Integer.class))
                .as("both legs moved to the new date")
                .isEqualTo(1);
    }

    @Test
    void a_note_only_edit_of_a_cross_currency_transfer_leaves_both_figures_alone() {
        Household household = seedHousehold();
        UUID pounds = createAccount(household.owner(), "Current", "4321");
        UUID euros = createAccountIn(household.owner(), "Holiday", "EUR");
        ApiResponse created = crossCurrency(household.owner(), pounds, euros);
        UUID id = UUID.fromString(created.stringField("id"));

        ApiResponse edited =
                household
                        .owner()
                        .patch(
                                "/api/transfers/" + id,
                                "{\"note\":\"holiday money\",\"version\":"
                                        + created.numberField("version")
                                        + "}");

        // Absent means unchanged, as it does in every other PATCH here. An earlier version
        // demanded the received figure on every edit, which made a note edit a 400 naming a
        // field the caller had no reason to send.
        assertThat(edited.status()).isEqualTo(200);
        assertThat(edited.stringField("sent")).isEqualTo("200.0000");
        assertThat(edited.stringField("received")).isEqualTo("228.4000");
        assertThat(edited.stringField("note")).isEqualTo("holiday money");
    }

    @Test
    void changing_one_side_of_a_cross_currency_transfer_leaves_the_other_where_it_was() {
        Household household = seedHousehold();
        UUID pounds = createAccount(household.owner(), "Current", "4321");
        UUID euros = createAccountIn(household.owner(), "Holiday", "EUR");
        ApiResponse created = crossCurrency(household.owner(), pounds, euros);
        UUID id = UUID.fromString(created.stringField("id"));

        ApiResponse edited =
                household
                        .owner()
                        .patch(
                                "/api/transfers/" + id,
                                "{\"amount\":\"210.00\",\"version\":"
                                        + created.numberField("version")
                                        + "}");

        // Across currencies the two legs are independent facts. Recomputing the second from the
        // first would silently rewrite a balance nobody asked to change, at a rate we made up.
        assertThat(edited.stringField("sent")).isEqualTo("210.0000");
        assertThat(edited.stringField("received")).isEqualTo("228.4000");
        assertThat(household.owner().get("/api/accounts/" + euros).stringField("balance"))
                .isEqualTo("228.4000");
    }

    @Test
    void a_transfer_leg_cannot_be_logged_as_envelope_spending() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "4321");
        UUID savings = createAccount(household.owner(), "Savings", "8765");
        transfer(household.owner(), current, savings, "250.00");
        UUID groceries = createEnvelope(household.owner(), "Groceries", "400.00");

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/envelopes/" + groceries + "/entries",
                                "{\"transactionId\":\"" + legIds().get(0) + "\"}");

        // The leg looks exactly like a purchase. Without this guard the envelope's spent total
        // would include money that never left the household, wearing a SYNCED badge claiming a
        // real transaction backs it — and deleting the transfer would leave the entry behind,
        // because fk_envelope_entries_transaction is ON DELETE SET NULL. Unreachable before this
        // slice existed, reachable the moment it did, and found by a security review.
        assertThat(refused.status()).isEqualTo(400);
        assertThat(household.owner().get("/api/envelopes/" + groceries).stringField("spent"))
                .isEqualTo("0.0000");
    }

    @Test
    void an_edit_from_a_stale_copy_is_refused() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "4321");
        UUID savings = createAccount(household.owner(), "Savings", "8765");
        ApiResponse created = transfer(household.owner(), current, savings, "250.00");
        UUID id = UUID.fromString(created.stringField("id"));
        long stale = created.numberField("version");

        household
                .owner()
                .patch("/api/transfers/" + id, "{\"amount\":\"260.00\",\"version\":" + stale + "}");
        ApiResponse second =
                household
                        .owner()
                        .patch(
                                "/api/transfers/" + id,
                                "{\"amount\":\"270.00\",\"version\":" + stale + "}");

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.body()).contains("stale-version");
        assertThat(household.owner().get("/api/transfers/" + id).stringField("sent"))
                .isEqualTo("260.0000");
    }

    @Test
    void the_pairs_version_only_ever_moves_forward() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "4321");
        UUID savings = createAccount(household.owner(), "Savings", "8765");
        ApiResponse created = transfer(household.owner(), current, savings, "250.00");
        UUID id = UUID.fromString(created.stringField("id"));

        long first = created.numberField("version");
        long second =
                household
                        .owner()
                        .patch(
                                "/api/transfers/" + id,
                                "{\"amount\":\"260.00\",\"version\":" + first + "}")
                        .numberField("version");
        long third =
                household
                        .owner()
                        .patch(
                                "/api/transfers/" + id,
                                "{\"amount\":\"270.00\",\"version\":" + second + "}")
                        .numberField("version");

        // The sum of the two legs'. It is stale exactly when either leg is, and a version that
        // could go backwards would let a stale copy coincide with a later one.
        assertThat(second).isGreaterThan(first);
        assertThat(third).isGreaterThan(second);
    }

    @Test
    void deleting_a_transfer_removes_both_legs_and_restores_both_balances() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "4321");
        UUID savings = createAccount(household.owner(), "Savings", "8765");
        UUID id =
                UUID.fromString(
                        transfer(household.owner(), current, savings, "250.00").stringField("id"));

        assertThat(household.owner().delete("/api/transfers/" + id).status()).isEqualTo(204);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class))
                .isZero();
        assertThat(household.owner().get("/api/accounts/" + current).stringField("balance"))
                .as("back to its opening balance, with nothing left behind")
                .isEqualTo("100.0000");
        assertThat(household.owner().get("/api/transfers/" + id).status()).isEqualTo(404);
    }

    @Test
    void a_transfer_cannot_be_recorded_against_an_account_of_another_kind_of_id() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "4321");

        // A random UUID is not a cross-household test — MoneyCrossHouseholdIT does that with a
        // real second household. This only asserts that an unknown id is a 404, not a 500.
        assertThat(transfer(household.owner(), current, UUID.randomUUID(), "10.00").status())
                .isEqualTo(404);
    }

    @Test
    void a_malformed_group_id_is_refused_before_it_reaches_a_query() {
        Household household = seedHousehold();

        assertThat(household.owner().get("/api/transfers/not-a-uuid").status()).isEqualTo(400);
    }

    // ----------------------------------------------------------------------------------- roles

    /** A list rather than a sample — see {@code BillIT.everyRoute}. */
    static List<Endpoint> everyRoute() {
        return List.of(
                new Endpoint("POST", "/api/transfers", null),
                new Endpoint("GET", "/api/transfers/{id}", null),
                new Endpoint("PATCH", "/api/transfers/{id}", "{\"amount\":\"1.00\",\"version\":0}"),
                new Endpoint("DELETE", "/api/transfers/{id}", null));
    }

    static List<Endpoint> everyWrite() {
        return everyRoute().stream().filter(endpoint -> !"GET".equals(endpoint.method())).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void answers403ToAViewerOnEveryWrite(Endpoint endpoint) {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "4321");
        UUID savings = createAccount(household.owner(), "Savings", "8765");
        UUID id =
                UUID.fromString(
                        transfer(household.owner(), current, savings, "250.00").stringField("id"));

        ApiResponse response = endpoint.callWith(household.viewer(), id, current, savings);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.stringField("code")).isEqualTo("read-only-role");
    }

    @Test
    void a_viewer_may_read_a_transfer() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "4321");
        UUID savings = createAccount(household.owner(), "Savings", "8765");
        UUID id =
                UUID.fromString(
                        transfer(household.owner(), current, savings, "250.00").stringField("id"));

        assertThat(household.viewer().get("/api/transfers/" + id).stringField("sent"))
                .isEqualTo("250.0000");
    }

    @Test
    void a_member_may_move_money() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "4321");
        UUID savings = createAccount(household.owner(), "Savings", "8765");

        assertThat(transfer(household.member(), current, savings, "250.00").status())
                .isEqualTo(201);
    }

    // ------------------------------------------------------------------------------- internals

    record Endpoint(String method, String path, String body) {

        ApiResponse callWith(ApiClient client, UUID transferId, UUID from, UUID to) {
            String resolved = path.replace("{id}", transferId.toString());
            String payload =
                    "POST".equals(method)
                            ? """
                              {"fromAccountId":"%s","toAccountId":"%s","date":"%s","amount":"1.00"}
                              """
                                    .formatted(from, to, DATE)
                            : body;
            return client.send(method, resolved, payload);
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private ApiResponse transfer(ApiClient client, UUID from, UUID to, String amount) {
        return client.post(
                "/api/transfers",
                """
                {"fromAccountId":"%s","toAccountId":"%s","date":"%s","amount":"%s"}
                """
                        .formatted(from, to, DATE, amount));
    }

    /** An account in a currency other than the household's base, to make a pair cross-currency. */
    private UUID createAccountIn(ApiClient client, String name, String currency) {
        ApiResponse response =
                client.post(
                        "/api/accounts",
                        """
                        {"name":"%s","type":"SAVINGS","currency":"%s","openingBalance":"0.00"}
                        """
                                .formatted(name, currency));
        if (response.status() != 201) {
            throw new AssertionError("could not create an account: " + response.body());
        }
        return UUID.fromString(response.stringField("id"));
    }

    private ApiResponse crossCurrency(ApiClient client, UUID pounds, UUID euros) {
        return client.post(
                "/api/transfers",
                """
                {"fromAccountId":"%s","toAccountId":"%s","date":"%s",
                 "amount":"200.00","amountReceived":"228.40"}
                """
                        .formatted(pounds, euros, DATE));
    }

    private UUID createEnvelope(ApiClient client, String name, String amount) {
        ApiResponse response =
                client.post(
                        "/api/envelopes",
                        """
                        {"name":"%s","period":"2026-08","amount":"%s"}
                        """
                                .formatted(name, amount));
        if (response.status() != 201) {
            throw new AssertionError("could not create an envelope: " + response.body());
        }
        return UUID.fromString(response.stringField("id"));
    }

    private List<UUID> legIds() {
        return jdbc.queryForList(
                "SELECT id FROM transactions WHERE transfer_group_id IS NOT NULL ORDER BY amount",
                UUID.class);
    }
}
