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
 * Income over real HTTP against real PostgreSQL.
 *
 * <p>The cases that matter: that a month's income is the schedule counted rather than a flat
 * monthly figure, that a fortnightly wage is therefore worth more in a three-payday month, and that
 * nothing here is ever mistaken for money that arrived.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class IncomeIT extends ApiTestBase {

    @Test
    void a_monthly_salary_is_worth_itself_once_a_month() {
        Household household = seedHousehold();
        createIncome(household.owner(), "Salary", "2400.00", monthly(28), null);

        ApiResponse march = household.owner().get("/api/income?period=2099-03");

        assertThat(march.status()).isEqualTo(200);
        assertThat(march.stringField("total")).isEqualTo("2400.0000");
        assertThat(march.body()).contains("\"dates\":[\"2099-03-28\"]");
        assertThat(march.stringField("currency")).isEqualTo("GBP");
        assertThat(march.body())
                .as("a typed figure and one derived from history are different claims")
                .contains("\"sourceOfTruth\":\"MANUAL_ESTIMATE\"");
    }

    @Test
    void a_fortnightly_wage_is_worth_more_in_a_three_payday_month() {
        Household household = seedHousehold();
        createIncome(
                household.owner(),
                "Wages",
                "900.00",
                """
                {"kind":"FORTNIGHTLY","dayOfWeek":"FRIDAY","anchorDate":"2099-01-01"}
                """,
                null);

        // Counting in whole weeks from 1 January 2099, the matching Fridays are the 9th and 23rd
        // of January, and then every other Friday from there. May 2099 holds three of them.
        ApiResponse may = household.owner().get("/api/income?period=2099-05");
        assertThat(may.body()).contains("2099-05-01").contains("2099-05-15").contains("2099-05-29");
        assertThat(may.stringField("total"))
                .as("three paydays is the month the household actually has, not a rounding error")
                .isEqualTo("2700.0000");

        ApiResponse april = household.owner().get("/api/income?period=2099-04");
        assertThat(april.stringField("total")).isEqualTo("1800.0000");
        assertThat(april.body())
                .as("in step with the anchor, so a three-payday month does not shift the weeks")
                .contains("2099-04-03")
                .contains("2099-04-17")
                .doesNotContain("2099-04-10");
    }

    @Test
    void a_yearly_bonus_counts_in_its_own_month_and_nowhere_else() {
        Household household = seedHousehold();
        createIncome(
                household.owner(),
                "Bonus",
                "3000.00",
                "{\"kind\":\"YEARLY\",\"month\":12,\"dayOfMonth\":20}",
                null);

        assertThat(household.owner().get("/api/income?period=2099-12").stringField("total"))
                .isEqualTo("3000.0000");
        ApiResponse march = household.owner().get("/api/income?period=2099-03");
        assertThat(march.stringField("total")).isEqualTo("0.0000");
        // Still listed, with no arrivals. A source that vanishes for eleven months reads as lost.
        assertThat(march.body()).contains("Bonus").contains("\"dates\":[]");
    }

    @Test
    void income_can_name_the_account_it_lands_in_or_name_none() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "4321");

        ApiResponse attributed =
                createIncomeResponse(household.owner(), "Salary", "2400.00", monthly(28), current);
        assertThat(attributed.status()).isEqualTo(201);
        assertThat(attributed.stringField("accountName")).isEqualTo("Current");

        // Optional: a household may track income it cannot attribute to one account.
        ApiResponse unattributed =
                createIncomeResponse(household.owner(), "Cash work", "200.00", monthly(1), null);
        assertThat(unattributed.status()).isEqualTo(201);
        assertThat(unattributed.body()).contains("\"accountId\":null");
    }

    @Test
    void an_account_that_does_not_exist_is_refused() {
        Household household = seedHousehold();

        ApiResponse refused =
                createIncomeResponse(
                        household.owner(), "Salary", "2400.00", monthly(28), UUID.randomUUID());

        // Named for what it actually tests. A random UUID answers 404 even against a completely
        // unscoped findById, so this is NOT the cross-household control — that one needs a real
        // second household and lives in MoneyCrossHouseholdIT, which seeds one in plain SQL.
        assertThat(refused.status()).isEqualTo(404);
    }

    @Test
    void a_retired_source_is_not_income_the_household_expects() {
        Household household = seedHousehold();
        UUID salary = createIncome(household.owner(), "Salary", "2400.00", monthly(28), null);

        ApiResponse retired =
                household
                        .owner()
                        .patch(
                                "/api/income/" + salary,
                                "{\"active\":false,\"version\":"
                                        + versionOf(household, salary)
                                        + "}");
        assertThat(retired.status()).isEqualTo(200);

        // Counting a job somebody has left would overstate what there is to spend, by a salary.
        assertThat(household.owner().get("/api/income?period=2099-03").stringField("total"))
                .isEqualTo("0.0000");
        assertThat(
                        household
                                .owner()
                                .get("/api/income?period=2099-03&includeInactive=true")
                                .stringField("total"))
                .as("asking to see it is not asking to count it")
                .isEqualTo("0.0000");
        assertThat(household.owner().get("/api/income?period=2099-03&includeInactive=true").body())
                .contains("Salary");
    }

    @Test
    void a_viewer_sees_the_figures_and_changes_nothing() {
        Household household = seedHousehold();
        UUID salary = createIncome(household.owner(), "Salary", "2400.00", monthly(28), null);

        assertThat(household.viewer().get("/api/income?period=2099-03").stringField("total"))
                .isEqualTo("2400.0000");
        assertThat(
                        household
                                .viewer()
                                .patch(
                                        "/api/income/" + salary,
                                        "{\"amount\":\"1.00\",\"version\":0}")
                                .status())
                .isEqualTo(403);
        assertThat(household.viewer().delete("/api/income/" + salary).status()).isEqualTo(403);
    }

    @Test
    void income_never_writes_a_transaction() {
        Household household = seedHousehold();
        createIncome(household.owner(), "Salary", "2400.00", monthly(28), null);

        household.owner().get("/api/income?period=2099-03");

        // An expectation, not a record of money that arrived (ADR-0025).
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class))
                .isZero();
    }

    @Test
    void two_sources_cannot_share_a_name() {
        Household household = seedHousehold();
        createIncome(household.owner(), "Salary", "2400.00", monthly(28), null);

        ApiResponse again =
                createIncomeResponse(household.owner(), "salary", "2400.00", monthly(28), null);

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body()).contains("income-name-taken");
    }

    @Test
    void an_amount_over_the_scale_money_has_is_refused_rather_than_rounded() {
        Household household = seedHousehold();

        assertThat(
                        createIncomeResponse(
                                        household.owner(),
                                        "Salary",
                                        "2400.00001",
                                        monthly(28),
                                        null)
                                .status())
                .as("rounding somebody's money without telling them is what ADR-0006 prevents")
                .isEqualTo(400);
    }

    @Test
    void editing_one_field_leaves_the_others_as_they_were() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "4321");
        UUID salary = createIncome(household.owner(), "Salary", "2400.00", monthly(28), current);

        ApiResponse raised =
                household
                        .owner()
                        .patch(
                                "/api/income/" + salary,
                                "{\"amount\":\"2600.00\",\"version\":"
                                        + versionOf(household, salary)
                                        + "}");

        assertThat(raised.status()).isEqualTo(200);
        assertThat(raised.stringField("amount")).isEqualTo("2600.0000");
        assertThat(raised.stringField("name")).isEqualTo("Salary");
        assertThat(raised.stringField("accountName")).isEqualTo("Current");
        assertThat(raised.body()).contains("\"dayOfMonth\":28");
    }

    @Test
    void an_account_can_be_cleared_without_clearing_anything_else() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "4321");
        UUID salary = createIncome(household.owner(), "Salary", "2400.00", monthly(28), current);

        ApiResponse cleared =
                household
                        .owner()
                        .patch(
                                "/api/income/" + salary,
                                "{\"accountId\":null,\"version\":"
                                        + versionOf(household, salary)
                                        + "}");

        // An explicit null clears it; an absent field does not.
        assertThat(cleared.body()).contains("\"accountId\":null").contains("\"accountName\":null");
        assertThat(cleared.stringField("amount")).isEqualTo("2400.0000");
    }

    @Test
    void a_schedule_can_change_kind_entirely() {
        Household household = seedHousehold();
        UUID salary = createIncome(household.owner(), "Salary", "2400.00", monthly(28), null);

        ApiResponse weekly =
                household
                        .owner()
                        .patch(
                                "/api/income/" + salary,
                                """
                                {"schedule":{"kind":"WEEKLY","dayOfWeek":"FRIDAY"},
                                 "amount":"600.00","version":%d}
                                """
                                        .formatted(versionOf(household, salary)));

        assertThat(weekly.status()).isEqualTo(200);
        assertThat(weekly.body()).contains("\"dayOfMonth\":null");
        // Four Fridays in March 2099.
        assertThat(household.owner().get("/api/income?period=2099-03").stringField("total"))
                .isEqualTo("2400.0000");
    }

    @Test
    void an_edit_from_a_stale_copy_is_refused_rather_than_applied() {
        Household household = seedHousehold();
        UUID salary = createIncome(household.owner(), "Salary", "2400.00", monthly(28), null);
        long stale = versionOf(household, salary);
        household
                .owner()
                .patch(
                        "/api/income/" + salary,
                        "{\"amount\":\"2500.00\",\"version\":" + stale + "}");

        ApiResponse second =
                household
                        .owner()
                        .patch(
                                "/api/income/" + salary,
                                "{\"amount\":\"2600.00\",\"version\":" + stale + "}");

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.body()).contains("stale-version");
        assertThat(
                        household
                                .owner()
                                .get("/api/income/" + salary + "?period=2099-03")
                                .stringField("amount"))
                .isEqualTo("2500.0000");
    }

    @Test
    void an_edit_without_a_version_is_refused() {
        Household household = seedHousehold();
        UUID salary = createIncome(household.owner(), "Salary", "2400.00", monthly(28), null);

        assertThat(
                        household
                                .owner()
                                .patch("/api/income/" + salary, "{\"amount\":\"1.00\"}")
                                .status())
                .isEqualTo(400);
    }

    @Test
    void a_retired_source_can_be_brought_back() {
        Household household = seedHousehold();
        UUID salary = createIncome(household.owner(), "Salary", "2400.00", monthly(28), null);
        household
                .owner()
                .patch(
                        "/api/income/" + salary,
                        "{\"active\":false,\"version\":" + versionOf(household, salary) + "}");

        ApiResponse restored =
                household
                        .owner()
                        .patch(
                                "/api/income/" + salary,
                                "{\"active\":true,\"version\":"
                                        + versionOf(household, salary)
                                        + "}");

        assertThat(restored.status()).isEqualTo(200);
        assertThat(household.owner().get("/api/income?period=2099-03").stringField("total"))
                .isEqualTo("2400.0000");
    }

    @Test
    void deleting_a_source_removes_it_and_touches_nothing_else() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "4321");
        UUID salary = createIncome(household.owner(), "Salary", "2400.00", monthly(28), current);

        assertThat(household.owner().delete("/api/income/" + salary).status()).isEqualTo(204);

        assertThat(household.owner().get("/api/income?period=2099-03").stringField("total"))
                .isEqualTo("0.0000");
        assertThat(household.owner().get("/api/accounts/" + current).status())
                .as("an income source was never the account it landed in")
                .isEqualTo(200);
    }

    @Test
    void a_member_may_do_everything_an_owner_may_do_here() {
        Household household = seedHousehold();
        UUID salary = createIncome(household.member(), "Salary", "2400.00", monthly(28), null);

        assertThat(household.member().delete("/api/income/" + salary).status()).isEqualTo(204);
    }

    // ----------------------------------------------------------------------------------- roles

    /** A list rather than a sample — see {@code BillIT.everyRoute}. */
    static List<Endpoint> everyRoute() {
        return List.of(
                new Endpoint("GET", "/api/income?period=2099-03", null),
                new Endpoint(
                        "POST",
                        "/api/income",
                        """
                        {"name":"New","amount":"10.00",
                         "schedule":{"kind":"MONTHLY","dayOfMonth":1}}
                        """),
                new Endpoint("GET", "/api/income/{id}?period=2099-03", null),
                new Endpoint("PATCH", "/api/income/{id}", "{\"version\":0,\"name\":\"Renamed\"}"),
                new Endpoint("DELETE", "/api/income/{id}", null));
    }

    static List<Endpoint> everyWrite() {
        return everyRoute().stream().filter(endpoint -> !"GET".equals(endpoint.method())).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void answers403ToAViewerOnEveryWrite(Endpoint endpoint) {
        Household household = seedHousehold();
        UUID salary = createIncome(household.owner(), "Salary", "2400.00", monthly(28), null);

        ApiResponse response = endpoint.callWith(household.viewer(), salary);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.stringField("code")).isEqualTo("read-only-role");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRoute")
    void the_bearer_transport_is_turned_away_from_no_route(Endpoint endpoint) {
        Household household = seedHousehold();
        UUID salary = createIncome(household.owner(), "Salary", "2400.00", monthly(28), null);
        ApiClient phone = mobile(OWNER_EMAIL, OWNER_SECRET, "Ada's phone");

        ApiResponse response = endpoint.callWith(phone, salary);

        // See {@code BillIT} for why this is "not turned away" rather than a comparison.
        assertThat(response.status()).isNotIn(401, 403);
        assertThat(response.status()).isLessThan(500);
    }

    record Endpoint(String method, String path, String body) {

        ApiResponse callWith(ApiClient client, UUID sourceId) {
            return client.send(method, path.replace("{id}", sourceId.toString()), body);
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    // ------------------------------------------------------------------------------- internals

    private static String monthly(int dayOfMonth) {
        return "{\"kind\":\"MONTHLY\",\"dayOfMonth\":" + dayOfMonth + "}";
    }

    private UUID createIncome(
            ApiClient client, String name, String amount, String schedule, UUID accountId) {
        ApiResponse response = createIncomeResponse(client, name, amount, schedule, accountId);
        if (response.status() != 201) {
            throw new AssertionError("could not create an income source: " + response.body());
        }
        return UUID.fromString(response.stringField("id"));
    }

    private ApiResponse createIncomeResponse(
            ApiClient client, String name, String amount, String schedule, UUID accountId) {
        return client.post(
                "/api/income",
                """
                {"name":"%s","amount":"%s","schedule":%s,"accountId":%s}
                """
                        .formatted(
                                name,
                                amount,
                                schedule,
                                accountId == null ? "null" : "\"" + accountId + "\""));
    }

    private long versionOf(Household household, UUID sourceId) {
        return household
                .owner()
                .get("/api/income/" + sourceId + "?period=2099-03")
                .numberField("version");
    }
}
