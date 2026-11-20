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
 * Envelopes over real HTTP against real PostgreSQL.
 *
 * <p>The cases chosen are the ones that would make the feature useless or wrong: that {@code
 * remaining} is computed and follows every entry, that it goes <b>negative</b> rather than being
 * clamped, that an amount alone is a valid entry, and that one transaction cannot be counted in two
 * envelopes.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EnvelopeIT extends ApiTestBase {

    private static final String AUGUST = "2026-08";

    // ------------------------------------------------------------------------------- envelopes

    @Test
    void an_envelope_starts_with_everything_remaining_and_nothing_logged() {
        Household household = seedHousehold();

        ApiResponse created = createEnvelope(household.owner(), "Groceries", AUGUST, "400.00");

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.stringField("amount")).isEqualTo("400.0000");
        assertThat(created.stringField("spent")).isEqualTo("0.0000");
        assertThat(created.stringField("remaining")).isEqualTo("400.0000");
        assertThat(created.body()).contains("\"empty\":true").contains("\"entryCount\":0");
        assertThat(created.stringField("currency"))
                .as("an envelope is in the household's base currency and names no other")
                .isEqualTo("GBP");
    }

    @Test
    void logging_an_entry_moves_remaining_immediately() {
        Household household = seedHousehold();
        UUID groceries =
                envelopeId(createEnvelope(household.owner(), "Groceries", AUGUST, "400.00"));

        logEntry(household.owner(), groceries, "86.40");
        logEntry(household.owner(), groceries, "104.75");

        ApiResponse read = household.owner().get("/api/envelopes/" + groceries);
        assertThat(read.stringField("spent")).isEqualTo("191.1500");
        // The whole point of the feature: the figure exists while somebody is still in the shop.
        assertThat(read.stringField("remaining")).isEqualTo("208.8500");
        assertThat(read.body()).contains("\"empty\":false").contains("\"entryCount\":2");
    }

    @Test
    void an_overspent_envelope_shows_a_negative_remaining_rather_than_zero() {
        Household household = seedHousehold();
        UUID small = envelopeId(createEnvelope(household.owner(), "Eating out", AUGUST, "30.00"));

        logEntry(household.owner(), small, "41.20");

        // "You are £11.20 over" is the information. Clamping to zero would hide exactly the thing
        // the person needs to know (ADR-0031 — there is no rollover to absorb it either).
        assertThat(household.owner().get("/api/envelopes/" + small).stringField("remaining"))
                .isEqualTo("-11.2000");
    }

    @Test
    void an_amount_alone_is_a_valid_entry() {
        Household household = seedHousehold();
        UUID groceries =
                envelopeId(createEnvelope(household.owner(), "Groceries", AUGUST, "400.00"));

        ApiResponse logged =
                household
                        .owner()
                        .post(
                                "/api/envelopes/" + groceries + "/entries",
                                """
                                {"amount":"12.50"}
                                """);

        // The action somebody takes eighty times a month, usually one-handed, usually in a queue.
        assertThat(logged.status()).isEqualTo(201);
        assertThat(logged.stringField("source")).isEqualTo("MANUAL");
        assertThat(logged.body()).contains("\"payeeId\":null").contains("\"note\":null");
        assertThat(logged.stringField("date"))
                .as("no date given means today, so nothing blocks the entry")
                .isNotBlank();
    }

    @Test
    void an_entry_of_zero_or_less_is_refused() {
        Household household = seedHousehold();
        UUID groceries =
                envelopeId(createEnvelope(household.owner(), "Groceries", AUGUST, "400.00"));

        assertThat(
                        household
                                .owner()
                                .post(
                                        "/api/envelopes/" + groceries + "/entries",
                                        "{\"amount\":\"0.00\"}")
                                .status())
                .isEqualTo(400);
        assertThat(
                        household
                                .owner()
                                .post(
                                        "/api/envelopes/" + groceries + "/entries",
                                        "{\"amount\":\"-5.00\"}")
                                .status())
                .as("an entry records how much was used, which only goes one way")
                .isEqualTo(400);
    }

    @Test
    void an_envelope_of_zero_is_allowed_but_a_negative_one_is_not() {
        Household household = seedHousehold();

        // Deciding to spend nothing on something is a real decision.
        assertThat(createEnvelope(household.owner(), "Takeaways", AUGUST, "0.00").status())
                .isEqualTo(201);
        assertThat(createEnvelope(household.owner(), "Impossible", AUGUST, "-10.00").status())
                .as("you cannot set aside minus ten pounds")
                .isEqualTo(400);
    }

    @Test
    void the_same_name_is_refused_in_one_month_and_allowed_in_another() {
        Household household = seedHousehold();
        createEnvelope(household.owner(), "Groceries", AUGUST, "400.00");

        ApiResponse duplicate = createEnvelope(household.owner(), "groceries", AUGUST, "500.00");
        assertThat(duplicate.status())
                .as("case-insensitively: nothing downstream could tell the two apart")
                .isEqualTo(409);
        assertThat(duplicate.body()).contains("envelope-name-taken");

        // September's Groceries is a different decision and must exist alongside August's.
        assertThat(createEnvelope(household.owner(), "Groceries", "2026-09", "420.00").status())
                .isEqualTo(201);
    }

    @Test
    void a_period_lists_only_its_own_envelopes_and_totals_them() {
        Household household = seedHousehold();
        UUID groceries =
                envelopeId(createEnvelope(household.owner(), "Groceries", AUGUST, "400.00"));
        createEnvelope(household.owner(), "Fuel", AUGUST, "100.00");
        createEnvelope(household.owner(), "Groceries", "2026-09", "420.00");
        logEntry(household.owner(), groceries, "86.40");

        ApiResponse august = household.owner().get("/api/envelopes?period=" + AUGUST);

        assertThat(august.numberField("totalElements")).isEqualTo(2);
        assertThat(august.stringField("totalAllocated")).isEqualTo("500.0000");
        assertThat(august.stringField("totalSpent")).isEqualTo("86.4000");
        assertThat(august.stringField("totalRemaining")).isEqualTo("413.6000");
        assertThat(august.body()).doesNotContain("2026-09");
    }

    @Test
    void the_period_is_a_label_not_a_bound_on_entry_dates() {
        Household household = seedHousehold();
        UUID groceries =
                envelopeId(createEnvelope(household.owner(), "Groceries", AUGUST, "400.00"));

        // A date outside August, logged into August's envelope on purpose. An entry belongs to an
        // envelope because somebody logged it there (ADR-0031) — this is what lets slice 9 move
        // the month boundary without rewriting a single row.
        ApiResponse logged =
                household
                        .owner()
                        .post(
                                "/api/envelopes/" + groceries + "/entries",
                                """
                                {"amount":"20.00","date":"2026-07-28"}
                                """);

        assertThat(logged.status()).isEqualTo(201);
        assertThat(household.owner().get("/api/envelopes/" + groceries).stringField("spent"))
                .isEqualTo("20.0000");
    }

    @Test
    void the_periods_endpoint_lists_the_months_that_have_envelopes_newest_first() {
        Household household = seedHousehold();
        createEnvelope(household.owner(), "Groceries", AUGUST, "400.00");
        createEnvelope(household.owner(), "Groceries", "2026-09", "420.00");

        assertThat(household.owner().get("/api/envelopes/periods").body())
                .isEqualTo("[\"2026-09\",\"2026-08\"]");
    }

    // --------------------------------------------------------------------------- transactions

    @Test
    void attaching_a_transaction_takes_the_figure_from_it_and_marks_the_entry_synced() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        UUID transaction =
                createTransaction(household.owner(), account, "2026-08-03", "-86.4000", null);
        UUID groceries =
                envelopeId(createEnvelope(household.owner(), "Groceries", AUGUST, "400.00"));

        ApiResponse logged = attach(household.owner(), groceries, transaction);

        assertThat(logged.status()).isEqualTo(201);
        assertThat(logged.stringField("source")).isEqualTo("SYNCED");
        // Positive: money out of an account is a negative transaction, and an entry records how
        // much of the decided amount was used.
        assertThat(logged.stringField("amount")).isEqualTo("86.4000");
        assertThat(logged.stringField("date"))
                .as("the transaction's own date, not today")
                .isEqualTo("2026-08-03");
        assertThat(logged.stringField("transactionId")).isEqualTo(transaction.toString());
    }

    @Test
    void a_transaction_cannot_be_counted_in_two_envelopes() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        UUID transaction =
                createTransaction(household.owner(), account, "2026-08-03", "-86.4000", null);
        UUID groceries =
                envelopeId(createEnvelope(household.owner(), "Groceries", AUGUST, "400.00"));
        UUID adhoc = envelopeId(createEnvelope(household.owner(), "Adhoc", AUGUST, "200.00"));
        attach(household.owner(), groceries, transaction);

        ApiResponse refused = attach(household.owner(), adhoc, transaction);

        // Counting it twice would overstate spending in both and understate what is left in each —
        // and both figures would still look plausible, which is why nobody would notice (ADR-0031).
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body()).contains("transaction-already-in-envelope");
        assertThat(household.owner().get("/api/envelopes/" + adhoc).stringField("spent"))
                .isEqualTo("0.0000");
    }

    @Test
    void money_arriving_cannot_be_logged_as_envelope_spending() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        UUID income =
                createTransaction(household.owner(), account, "2026-08-01", "3200.0000", null);
        UUID groceries =
                envelopeId(createEnvelope(household.owner(), "Groceries", AUGUST, "400.00"));

        ApiResponse refused = attach(household.owner(), groceries, income);

        // Silently flipping the sign would make the envelope look emptier than it is.
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("transactionId");
    }

    @Test
    void deleting_an_attached_transaction_keeps_the_entry_and_the_remaining_figure() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        UUID transaction =
                createTransaction(household.owner(), account, "2026-08-03", "-86.4000", null);
        UUID groceries =
                envelopeId(createEnvelope(household.owner(), "Groceries", AUGUST, "400.00"));
        attach(household.owner(), groceries, transaction);

        assertThat(household.owner().delete("/api/transactions/" + transaction).status())
                .isEqualTo(204);

        // Somebody decided that money came out of this envelope. The plan does not stop being true
        // because the record of the purchase was removed.
        ApiResponse read = household.owner().get("/api/envelopes/" + groceries);
        assertThat(read.stringField("spent")).isEqualTo("86.4000");
        assertThat(household.owner().get("/api/envelopes/" + groceries + "/entries").body())
                .contains("\"transactionId\":null")
                .contains("SYNCED");
    }

    @Test
    void an_amount_and_a_transaction_together_are_refused() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        UUID transaction =
                createTransaction(household.owner(), account, "2026-08-03", "-86.4000", null);
        UUID groceries =
                envelopeId(createEnvelope(household.owner(), "Groceries", AUGUST, "400.00"));

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/envelopes/" + groceries + "/entries",
                                """
                                {"amount":"10.00","transactionId":"%s"}
                                """
                                        .formatted(transaction));

        assertThat(refused.status())
                .as("the two figures could disagree, and nothing could say which was meant")
                .isEqualTo(400);
        assertThat(
                        household
                                .owner()
                                .post("/api/envelopes/" + groceries + "/entries", "{}")
                                .status())
                .as("and neither is not an entry at all")
                .isEqualTo(400);
    }

    // --------------------------------------------------------------------------------- the log

    @Test
    void the_log_is_newest_first_and_says_where_each_row_came_from() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        UUID transaction =
                createTransaction(household.owner(), account, "2026-08-20", "-9.9900", null);
        UUID groceries =
                envelopeId(createEnvelope(household.owner(), "Groceries", AUGUST, "400.00"));
        household
                .owner()
                .post(
                        "/api/envelopes/" + groceries + "/entries",
                        "{\"amount\":\"5.00\",\"date\":\"2026-08-01\"}");
        attach(household.owner(), groceries, transaction);

        String body = household.owner().get("/api/envelopes/" + groceries + "/entries").body();

        assertThat(body.indexOf("2026-08-20")).isLessThan(body.indexOf("2026-08-01"));
        // Both kinds on the same screen, each saying which it is.
        assertThat(body).contains("SYNCED").contains("MANUAL");
    }

    @Test
    void correcting_an_entry_moves_remaining_and_leaves_the_rest_alone() {
        Household household = seedHousehold();
        UUID groceries =
                envelopeId(createEnvelope(household.owner(), "Groceries", AUGUST, "400.00"));
        ApiResponse logged =
                household
                        .owner()
                        .post(
                                "/api/envelopes/" + groceries + "/entries",
                                """
                                {"amount":"86.40","payeeName":"Greenfield Market","note":"big shop"}
                                """);

        ApiResponse corrected =
                household
                        .owner()
                        .patch(
                                "/api/envelopes/"
                                        + groceries
                                        + "/entries/"
                                        + logged.stringField("id"),
                                """
                                {"amount":"96.40","version":%d}
                                """
                                        .formatted(logged.numberField("version")));

        assertThat(corrected.status()).isEqualTo(200);
        assertThat(corrected.stringField("payeeName"))
                .as("an absent payeeName must not clear the payee")
                .isEqualTo("Greenfield Market");
        assertThat(corrected.stringField("note")).isEqualTo("big shop");
        assertThat(household.owner().get("/api/envelopes/" + groceries).stringField("remaining"))
                .isEqualTo("303.6000");
    }

    @Test
    void deleting_an_entry_gives_the_money_back_to_the_envelope() {
        Household household = seedHousehold();
        UUID groceries =
                envelopeId(createEnvelope(household.owner(), "Groceries", AUGUST, "400.00"));
        ApiResponse logged = logEntry(household.owner(), groceries, "86.40");

        assertThat(
                        household
                                .owner()
                                .delete(
                                        "/api/envelopes/"
                                                + groceries
                                                + "/entries/"
                                                + logged.stringField("id"))
                                .status())
                .isEqualTo(204);

        assertThat(household.owner().get("/api/envelopes/" + groceries).stringField("remaining"))
                .isEqualTo("400.0000");
    }

    @Test
    void an_entry_cannot_be_reached_through_an_envelope_it_is_not_in() {
        Household household = seedHousehold();
        UUID groceries =
                envelopeId(createEnvelope(household.owner(), "Groceries", AUGUST, "400.00"));
        UUID adhoc = envelopeId(createEnvelope(household.owner(), "Adhoc", AUGUST, "200.00"));
        ApiResponse logged = logEntry(household.owner(), groceries, "86.40");

        // Same household, wrong envelope. Permitting it would let the entry be edited through a
        // URL naming an envelope it is not in, and the response would show it moving between
        // remaining figures.
        assertThat(
                        household
                                .owner()
                                .delete(
                                        "/api/envelopes/"
                                                + adhoc
                                                + "/entries/"
                                                + logged.stringField("id"))
                                .status())
                .isEqualTo(404);
    }

    @Test
    void an_archived_envelope_takes_no_new_entries_and_leaves_the_old_ones_readable() {
        Household household = seedHousehold();
        UUID groceries =
                envelopeId(createEnvelope(household.owner(), "Groceries", AUGUST, "400.00"));
        logEntry(household.owner(), groceries, "86.40");
        household.owner().post("/api/envelopes/" + groceries + "/archive", null);

        ApiResponse refused =
                household
                        .owner()
                        .post("/api/envelopes/" + groceries + "/entries", "{\"amount\":\"5.00\"}");

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body()).contains("envelope-archived");
        assertThat(household.owner().get("/api/envelopes/" + groceries + "/entries").body())
                .as("its log still reads correctly, which is why archiving exists")
                .contains("86.4000");
        assertThat(household.owner().get("/api/envelopes?period=" + AUGUST).body())
                .doesNotContain("Groceries");
        assertThat(
                        household
                                .owner()
                                .get("/api/envelopes?period=" + AUGUST + "&includeArchived=true")
                                .body())
                .contains("Groceries");
    }

    @Test
    void deleting_an_envelope_takes_its_log_and_no_transactions() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        UUID transaction =
                createTransaction(household.owner(), account, "2026-08-03", "-86.4000", null);
        UUID groceries =
                envelopeId(createEnvelope(household.owner(), "Groceries", AUGUST, "400.00"));
        attach(household.owner(), groceries, transaction);

        assertThat(household.owner().delete("/api/envelopes/" + groceries).status()).isEqualTo(204);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM envelope_entries", Long.class))
                .isZero();
        assertThat(household.owner().get("/api/transactions/" + transaction).status())
                .as("a transaction outlives any plan it was filed under")
                .isEqualTo(200);
    }

    // ----------------------------------------------------------------------------------- roles

    /** Method, path and body for each route, with {@code {id}} to be substituted. */
    static List<Endpoint> everyRoute() {
        return List.of(
                new Endpoint("GET", "/api/envelopes?period=" + AUGUST, null),
                new Endpoint(
                        "POST",
                        "/api/envelopes",
                        "{\"name\":\"New\",\"period\":\"" + AUGUST + "\",\"amount\":\"10.00\"}"),
                new Endpoint("GET", "/api/envelopes/{id}", null),
                new Endpoint(
                        "PATCH", "/api/envelopes/{id}", "{\"version\":0,\"name\":\"Renamed\"}"),
                new Endpoint("POST", "/api/envelopes/{id}/archive", null),
                new Endpoint("POST", "/api/envelopes/{id}/unarchive", null),
                new Endpoint("DELETE", "/api/envelopes/{id}", null),
                new Endpoint("GET", "/api/envelopes/{id}/entries", null),
                new Endpoint("POST", "/api/envelopes/{id}/entries", "{\"amount\":\"12.00\"}"));
    }

    /** The seven that change something. A {@code VIEWER} gets {@code 403} from every one. */
    static List<Endpoint> everyWrite() {
        return everyRoute().stream().filter(endpoint -> !"GET".equals(endpoint.method())).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void answers403ToAViewerOnEveryWrite(Endpoint endpoint) {
        Household household = seedHousehold();
        UUID groceries =
                envelopeId(createEnvelope(household.owner(), "Groceries", AUGUST, "400.00"));

        ApiResponse response = endpoint.callWith(household.viewer(), groceries);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.stringField("code")).isEqualTo("read-only-role");
    }

    @Test
    void a_viewer_may_read_an_envelope_and_its_log() {
        Household household = seedHousehold();
        UUID groceries =
                envelopeId(createEnvelope(household.owner(), "Groceries", AUGUST, "400.00"));
        logEntry(household.owner(), groceries, "86.40");

        assertThat(household.viewer().get("/api/envelopes?period=" + AUGUST).status())
                .isEqualTo(200);
        assertThat(household.viewer().get("/api/envelopes/" + groceries + "/entries").body())
                .contains("86.4000");
    }

    @Test
    void a_member_may_log_spend() {
        Household household = seedHousehold();
        UUID groceries =
                envelopeId(createEnvelope(household.owner(), "Groceries", AUGUST, "400.00"));

        assertThat(logEntry(household.member(), groceries, "12.00").status()).isEqualTo(201);
    }

    @Test
    void a_stale_version_is_refused_rather_than_overwriting_somebody_elses_edit() {
        Household household = seedHousehold();
        UUID groceries =
                envelopeId(createEnvelope(household.owner(), "Groceries", AUGUST, "400.00"));
        long stale = household.owner().get("/api/envelopes/" + groceries).numberField("version");

        household
                .owner()
                .patch(
                        "/api/envelopes/" + groceries,
                        "{\"amount\":\"450.00\",\"version\":%d}".formatted(stale));

        ApiResponse second =
                household
                        .member()
                        .patch(
                                "/api/envelopes/" + groceries,
                                "{\"amount\":\"500.00\",\"version\":%d}".formatted(stale));

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.body()).contains("stale-version");
    }

    @Test
    void
            an_envelope_that_is_not_this_households_is_indistinguishable_from_one_that_never_existed() {
        Household household = seedHousehold();

        assertThat(household.owner().get("/api/envelopes/" + UUID.randomUUID()).status())
                .isEqualTo(404);
    }

    // ------------------------------------------------------------------------------- fixtures

    private ApiResponse createEnvelope(
            ApiClient client, String name, String period, String amount) {
        return client.post(
                "/api/envelopes",
                """
                {"name":"%s","period":"%s","amount":"%s"}
                """
                        .formatted(name, period, amount));
    }

    private static UUID envelopeId(ApiResponse created) {
        if (created.status() != 201) {
            throw new AssertionError("could not create an envelope: " + created.body());
        }
        return UUID.fromString(created.stringField("id"));
    }

    private ApiResponse logEntry(ApiClient client, UUID envelopeId, String amount) {
        return client.post(
                "/api/envelopes/" + envelopeId + "/entries",
                """
                {"amount":"%s"}
                """
                        .formatted(amount));
    }

    private ApiResponse attach(ApiClient client, UUID envelopeId, UUID transactionId) {
        return client.post(
                "/api/envelopes/" + envelopeId + "/entries",
                """
                {"transactionId":"%s"}
                """
                        .formatted(transactionId));
    }

    /** Method, path and body for one route, with {@code {id}} to be substituted. */
    record Endpoint(String method, String path, String body) {

        ApiResponse callWith(ApiClient client, UUID envelopeId) {
            return client.send(method, path.replace("{id}", envelopeId.toString()), body);
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }
}
