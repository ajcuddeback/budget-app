package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * "These two rows are one transfer", and the split that undoes it (docs/features/file-import.md
 * rule 12, decision 7).
 *
 * <p>Every refusal in the rule's table is here, and each asserts that <b>nothing was written</b>:
 * neither row gained a group id, lost its category or moved a version.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TransferPairingIT extends ImportApiTestBase {

    @Test
    void a_same_currency_pair_becomes_a_transfer_and_neither_balance_moves() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", null);
        UUID savings = createAccount(household.owner(), "Savings", null);
        UUID groceries = seededCategoryId("Groceries");
        UUID out =
                createTransaction(household.owner(), current, "2026-08-03", "-200.00", groceries);
        UUID in = createTransaction(household.owner(), savings, "2026-08-05", "200.00", null);
        jdbc.update(
                "UPDATE transactions SET external_id = 'ofx1:" + "a".repeat(64) + "' WHERE id = ?",
                out);
        String before = balances(household, current, savings);

        ApiResponse paired = pair(household.owner(), out, 0, in, 0);

        assertThat(paired.status()).isEqualTo(200);
        assertThat(paired.stringField("fromDate")).isEqualTo("2026-08-03");
        assertThat(paired.stringField("toDate"))
                .as("each leg keeps its own bank's date")
                .isEqualTo("2026-08-05");
        assertThat(paired.stringField("sent")).isEqualTo("200.0000");
        assertThat(paired.numberField("version")).isEqualTo(2);
        assertThat(balances(household, current, savings)).isEqualTo(before);
        Map<String, Object> leg =
                jdbc.queryForMap(
                        "SELECT category_id, payee_id, external_id, transfer_group_id FROM transactions WHERE id = ?",
                        out);
        assertThat(leg.get("category_id")).isNull();
        assertThat(leg.get("payee_id")).isNull();
        assertThat(leg.get("external_id"))
                .as("kept, so a later statement still skips it")
                .isEqualTo("ofx1:" + "a".repeat(64));
        assertThat(household.owner().get("/api/transfers/" + paired.stringField("id")).status())
                .isEqualTo(200);
        assertThat(household.owner().get("/api/transactions").body()).contains("\"transfer\":true");
    }

    @Test
    void a_cross_currency_pair_needs_only_opposite_signs_and_keeps_both_figures() {
        Household household = seedHousehold();
        UUID pounds = createAccount(household.owner(), "Current", null);
        UUID euros = accountIn(household.owner(), "Holiday", "EUR");
        UUID out = createTransaction(household.owner(), pounds, "2026-08-03", "-200.00", null);
        UUID in = createTransaction(household.owner(), euros, "2026-08-04", "231.40", null);

        ApiResponse paired = pair(household.owner(), out, 0, in, 0);

        assertThat(paired.status()).isEqualTo(200);
        assertThat(paired.stringField("received")).isEqualTo("231.4000");
        assertThat(paired.body()).contains("\"crossCurrency\":true");
    }

    @Test
    void the_imported_rows_keep_their_import_batch() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "2211");
        UUID savings = createAccount(household.owner(), "Savings", null);
        previewThenCommit(household.owner(), current, "OFX", fixture("ofx1-happy.qfx"), null);
        UUID salary =
                jdbc.queryForObject("SELECT id FROM transactions WHERE amount = 2400", UUID.class);
        UUID moved = createTransaction(household.owner(), savings, "2026-08-06", "-2400.00", null);

        assertThat(pair(household.owner(), salary, 0, moved, 0).status()).isEqualTo(200);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT import_batch_id FROM transactions WHERE id = ?",
                                UUID.class,
                                salary))
                .isNotNull();
    }

    // ---------------------------------------------------------------------------------- refusals

    @Test
    void two_rows_in_one_account_are_refused_and_nothing_changes() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", null);
        UUID out = createTransaction(household.owner(), current, "2026-08-03", "-200.00", null);
        UUID in = createTransaction(household.owner(), current, "2026-08-05", "200.00", null);

        assertRefused(
                pair(household.owner(), out, 0, in, 0),
                422,
                "transfer-pair-mismatch",
                "same-account");
        assertUnchanged(out, in);
    }

    @Test
    void a_row_paired_with_itself_is_refused() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", null);
        UUID row = createTransaction(household.owner(), current, "2026-08-03", "-200.00", null);

        assertRefused(
                pair(household.owner(), row, 0, row, 0),
                422,
                "transfer-pair-mismatch",
                "same-account");
    }

    /**
     * Slice-12 security audit, medium 3: a transfer is not spending, so a row logged against an
     * envelope must not become a leg — the envelope would go on counting money that never left the
     * household. Either side of the pair, and nothing written.
     */
    @Test
    void a_row_logged_against_an_envelope_cannot_be_paired_from_either_side() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", null);
        UUID savings = createAccount(household.owner(), "Savings", null);
        UUID out = createTransaction(household.owner(), current, "2026-08-03", "-250.00", null);
        UUID in = createTransaction(household.owner(), savings, "2026-08-03", "250.00", null);
        ApiResponse envelope =
                household
                        .owner()
                        .post(
                                "/api/envelopes",
                                "{\"name\":\"Groceries\",\"period\":\"2026-08\",\"amount\":\"400.00\"}");
        ApiResponse logged =
                household
                        .owner()
                        .post(
                                "/api/envelopes/" + envelope.stringField("id") + "/entries",
                                "{\"transactionId\":\"" + out + "\"}");
        assertThat(logged.status()).isEqualTo(201);

        assertRefused(
                pair(household.owner(), out, 0, in, 0), 409, "transfer-leg-in-envelope", null);
        assertRefused(
                pair(household.owner(), in, 0, out, 0), 409, "transfer-leg-in-envelope", null);
        assertUnchanged(out, in);
    }

    @Test
    void two_rows_with_the_same_sign_are_refused() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", null);
        UUID savings = createAccount(household.owner(), "Savings", null);
        UUID one = createTransaction(household.owner(), current, "2026-08-03", "-200.00", null);
        UUID two = createTransaction(household.owner(), savings, "2026-08-05", "-200.00", null);

        assertRefused(
                pair(household.owner(), one, 0, two, 0),
                422,
                "transfer-pair-mismatch",
                "same-sign");
        assertUnchanged(one, two);
    }

    @Test
    void unequal_amounts_in_one_currency_are_refused_even_by_a_fee() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", null);
        UUID savings = createAccount(household.owner(), "Savings", null);
        UUID out = createTransaction(household.owner(), current, "2026-08-03", "-200.00", null);
        UUID in = createTransaction(household.owner(), savings, "2026-08-05", "199.50", null);

        assertRefused(
                pair(household.owner(), out, 0, in, 0),
                422,
                "transfer-pair-mismatch",
                "amounts-differ");
        assertUnchanged(out, in);
    }

    @Test
    void a_row_already_in_a_transfer_is_refused() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", null);
        UUID savings = createAccount(household.owner(), "Savings", null);
        UUID third = createAccount(household.owner(), "Third", null);
        household
                .owner()
                .post(
                        "/api/transfers",
                        "{\"fromAccountId\":\""
                                + current
                                + "\",\"toAccountId\":\""
                                + savings
                                + "\",\"date\":\"2026-08-03\",\"amount\":\"50.00\"}");
        UUID leg =
                jdbc.queryForObject(
                        "SELECT id FROM transactions WHERE account_id = ? AND amount < 0",
                        UUID.class,
                        current);
        UUID loose = createTransaction(household.owner(), third, "2026-08-03", "50.00", null);
        long legVersion =
                jdbc.queryForObject(
                        "SELECT version FROM transactions WHERE id = ?", Long.class, leg);

        assertRefused(
                pair(household.owner(), leg, legVersion, loose, 0),
                409,
                "transfer-leg-not-separate",
                null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT transfer_group_id FROM transactions WHERE id = ?",
                                UUID.class,
                                loose))
                .isNull();
    }

    @Test
    void a_stale_version_on_either_row_is_refused() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", null);
        UUID savings = createAccount(household.owner(), "Savings", null);
        UUID out = createTransaction(household.owner(), current, "2026-08-03", "-200.00", null);
        UUID in = createTransaction(household.owner(), savings, "2026-08-05", "200.00", null);

        assertRefused(pair(household.owner(), out, 7, in, 0), 409, "stale-version", null);
        assertRefused(pair(household.owner(), out, 0, in, 7), 409, "stale-version", null);
        assertUnchanged(out, in);
    }

    @Test
    void a_row_in_an_archived_account_is_refused() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", null);
        UUID savings = createAccount(household.owner(), "Savings", null);
        UUID out = createTransaction(household.owner(), current, "2026-08-03", "-200.00", null);
        UUID in = createTransaction(household.owner(), savings, "2026-08-05", "200.00", null);
        household.owner().post("/api/accounts/" + savings + "/archive", null);

        assertRefused(pair(household.owner(), out, 0, in, 0), 409, "account-archived", null);
        assertUnchanged(out, in);
    }

    @Test
    void a_viewer_is_refused_before_either_row_is_looked_up() {
        Household household = seedHousehold();

        assertRefused(
                pair(household.viewer(), UUID.randomUUID(), 0, UUID.randomUUID(), 0),
                403,
                "read-only-role",
                null);
    }

    @Test
    void two_pairings_racing_for_one_row_make_one_transfer_and_one_refusal() throws Exception {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", null);
        UUID savings = createAccount(household.owner(), "Savings", null);
        UUID third = createAccount(household.owner(), "Third", null);
        UUID out = createTransaction(household.owner(), current, "2026-08-03", "-200.00", null);
        UUID one = createTransaction(household.owner(), savings, "2026-08-05", "200.00", null);
        UUID two = createTransaction(household.owner(), third, "2026-08-05", "200.00", null);
        List<Callable<ApiResponse>> both =
                List.of(
                        () -> pair(household.owner(), out, 0, one, 0),
                        () -> pair(household.member(), out, 0, two, 0));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<ApiResponse> outcome : pool.invokeAll(both)) {
                statuses.add(outcome.get().status());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(DISTINCT transfer_group_id) FROM transactions WHERE transfer_group_id IS NOT NULL",
                                Long.class))
                .isEqualTo(1L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM transactions WHERE transfer_group_id IS NOT NULL",
                                Long.class))
                .isEqualTo(2L);
    }

    // ------------------------------------------------------------------------------------ split

    @Test
    void a_split_leaves_two_ordinary_rows_with_every_figure_where_it_was() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", null);
        UUID savings = createAccount(household.owner(), "Savings", null);
        UUID out =
                createTransaction(
                        household.owner(),
                        current,
                        "2026-08-03",
                        "-200.00",
                        seededCategoryId("Groceries"));
        UUID in = createTransaction(household.owner(), savings, "2026-08-05", "200.00", null);
        ApiResponse paired = pair(household.owner(), out, 0, in, 0);

        ApiResponse split =
                household
                        .owner()
                        .post(
                                "/api/transfers/" + paired.stringField("id") + "/split",
                                "{\"version\":" + paired.numberField("version") + "}");

        assertThat(split.status()).isEqualTo(200);
        assertThat(split.body()).contains("\"transfer\":false").doesNotContain("\"transfer\":true");
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT date::text AS date, amount::text AS amount, account_id, transfer_group_id, category_id FROM transactions ORDER BY amount");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("date", "2026-08-03")
                .containsEntry("amount", "-200.0000")
                .containsEntry("account_id", current);
        assertThat(rows.get(1))
                .containsEntry("date", "2026-08-05")
                .containsEntry("amount", "200.0000")
                .containsEntry("account_id", savings);
        assertThat(rows)
                .allSatisfy(
                        row -> {
                            assertThat(row.get("transfer_group_id")).isNull();
                            assertThat(row.get("category_id"))
                                    .as("comes back in Needs a category")
                                    .isNull();
                        });
    }

    @Test
    void a_stale_split_is_refused_and_a_viewer_cannot_split() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", null);
        UUID savings = createAccount(household.owner(), "Savings", null);
        UUID out = createTransaction(household.owner(), current, "2026-08-03", "-200.00", null);
        UUID in = createTransaction(household.owner(), savings, "2026-08-05", "200.00", null);
        String id = pair(household.owner(), out, 0, in, 0).stringField("id");

        assertRefused(
                household.owner().post("/api/transfers/" + id + "/split", "{\"version\":0}"),
                409,
                "stale-version",
                null);
        assertRefused(
                household.viewer().post("/api/transfers/" + id + "/split", "{\"version\":2}"),
                403,
                "read-only-role",
                null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM transactions WHERE transfer_group_id IS NOT NULL",
                                Long.class))
                .isEqualTo(2L);
    }

    @Test
    void a_split_works_on_a_transfer_made_with_move_money_too() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", null);
        UUID savings = createAccount(household.owner(), "Savings", null);
        ApiResponse made =
                household
                        .owner()
                        .post(
                                "/api/transfers",
                                "{\"fromAccountId\":\""
                                        + current
                                        + "\",\"toAccountId\":\""
                                        + savings
                                        + "\",\"date\":\"2026-08-03\",\"amount\":\"50.00\"}");

        ApiResponse split =
                household
                        .owner()
                        .post(
                                "/api/transfers/" + made.stringField("id") + "/split",
                                "{\"version\":" + made.numberField("version") + "}");

        assertThat(split.status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------- per-leg date and note

    @Test
    void editing_the_amount_of_a_paired_transfer_leaves_both_dates_alone() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", null);
        UUID savings = createAccount(household.owner(), "Savings", null);
        UUID out = createTransaction(household.owner(), current, "2026-08-03", "-200.00", null);
        UUID in = createTransaction(household.owner(), savings, "2026-08-05", "200.00", null);
        ApiResponse paired = pair(household.owner(), out, 0, in, 0);

        ApiResponse edited =
                household
                        .owner()
                        .patch(
                                "/api/transfers/" + paired.stringField("id"),
                                "{\"amount\":\"210.00\",\"version\":"
                                        + paired.numberField("version")
                                        + "}");

        assertThat(edited.status()).isEqualTo(200);
        assertThat(edited.stringField("sent")).isEqualTo("210.0000");
        assertThat(edited.stringField("fromDate")).isEqualTo("2026-08-03");
        assertThat(edited.stringField("toDate")).isEqualTo("2026-08-05");
    }

    @Test
    void a_date_sent_in_a_patch_moves_both_legs() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", null);
        UUID savings = createAccount(household.owner(), "Savings", null);
        UUID out = createTransaction(household.owner(), current, "2026-08-03", "-200.00", null);
        UUID in = createTransaction(household.owner(), savings, "2026-08-05", "200.00", null);
        ApiResponse paired = pair(household.owner(), out, 0, in, 0);

        ApiResponse edited =
                household
                        .owner()
                        .patch(
                                "/api/transfers/" + paired.stringField("id"),
                                "{\"date\":\"2026-08-04\",\"note\":\"rent pot\",\"version\":"
                                        + paired.numberField("version")
                                        + "}");

        assertThat(edited.stringField("fromDate")).isEqualTo("2026-08-04");
        assertThat(edited.stringField("toDate")).isEqualTo("2026-08-04");
        assertThat(edited.stringField("fromNote")).isEqualTo("rent pot");
        assertThat(edited.stringField("toNote")).isEqualTo("rent pot");
    }

    // ------------------------------------------------------------------------------- internals

    private ApiResponse pair(
            ApiClient client, UUID row, long version, UUID with, long withVersion) {
        return client.post(
                "/api/transactions/" + row + "/pair",
                "{\"withTransactionId\":\""
                        + with
                        + "\",\"version\":"
                        + version
                        + ",\"withVersion\":"
                        + withVersion
                        + "}");
    }

    private void assertRefused(ApiResponse response, int status, String code, String reason) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.stringField("code")).isEqualTo(code);
        if (reason != null) {
            assertThat(response.stringField("reason")).isEqualTo(reason);
        }
    }

    private void assertUnchanged(UUID... rows) {
        for (UUID row : rows) {
            Map<String, Object> state =
                    jdbc.queryForMap(
                            "SELECT transfer_group_id, version FROM transactions WHERE id = ?",
                            row);
            assertThat(state.get("transfer_group_id")).isNull();
            assertThat(state.get("version")).isEqualTo(0L);
        }
    }

    private String balances(Household household, UUID... accounts) {
        StringBuilder out = new StringBuilder();
        for (UUID account : accounts) {
            out.append(household.owner().get("/api/accounts/" + account).stringField("balance"))
                    .append(';');
        }
        return out.toString();
    }

    private UUID accountIn(ApiClient client, String name, String currency) {
        ApiResponse response =
                client.post(
                        "/api/accounts",
                        "{\"name\":\""
                                + name
                                + "\",\"type\":\"SAVINGS\",\"currency\":\""
                                + currency
                                + "\",\"openingBalance\":\"0.00\"}");
        return UUID.fromString(response.stringField("id"));
    }
}
