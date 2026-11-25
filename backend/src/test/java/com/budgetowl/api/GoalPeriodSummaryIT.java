package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The fifth term of "what's left this month": {@code goalsSetAside} (docs/features/goals.md,
 * decision 2), and the plan's extra that is reported beside it and never subtracted (decision 3).
 *
 * <p>Every case asserts <b>the number</b>, not that {@code left} went down: a term subtracted
 * twice, or the recorded figure subtracted instead of the greater-of rule, also makes it go down.
 *
 * <p>The figures are the designs' Japan goal at £110 a month. Every case is in the current month,
 * because the plan cannot be set for a past one — which is the property decision 4 exists for.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GoalPeriodSummaryIT extends ApiTestBase {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final YearMonth NOW = YearMonth.from(TODAY);
    private static final LocalDate LAST_MONTH = TODAY.withDayOfMonth(1).minusDays(1);

    @Test
    void a_household_with_no_goals_has_a_fifth_term_of_zero() {
        Household household = seedHousehold();
        createIncome(household.owner(), "2400.00");

        ApiResponse summary = summary(household.owner(), NOW);

        assertThat(summary.stringField("goalsSetAside")).isEqualTo("0.0000");
        assertThat(summary.numberField("goalsCounted")).isZero();
        assertThat(summary.stringField("left")).isEqualTo("2400.0000");
    }

    @Test
    void a_plan_counts_in_full_before_anything_is_recorded() {
        Household household = seedHousehold();
        createIncome(household.owner(), "2400.00");
        japan(household.owner());

        ApiResponse summary = summary(household.owner(), NOW);

        assertThat(summary.stringField("goalsSetAside")).isEqualTo("110.0000");
        assertThat(summary.stringField("left")).isEqualTo("2290.0000");
    }

    @Test
    void recording_less_than_the_plan_does_not_move_the_figure() {
        Household household = seedHousehold();
        UUID goal = japan(household.owner());
        contribute(household.owner(), goal, "50", TODAY);

        assertThat(summary(household.owner(), NOW).stringField("goalsSetAside"))
                .isEqualTo("110.0000");
    }

    @Test
    void a_top_up_above_the_plan_counts_in_full() {
        Household household = seedHousehold();
        createIncome(household.owner(), "2400.00");
        UUID goal = japan(household.owner());
        contribute(household.owner(), goal, "200", TODAY);

        ApiResponse summary = summary(household.owner(), NOW);

        assertThat(summary.stringField("goalsSetAside")).isEqualTo("200.0000");
        // left subtracts the greater-of figure, not the recorded one and not the plan: 2,400 − 200.
        assertThat(summary.stringField("left")).isEqualTo("2200.0000");
    }

    @Test
    void a_withdrawal_never_raises_what_is_left() {
        Household household = seedHousehold();
        UUID goal = japan(household.owner());
        contribute(household.owner(), goal, "890", LAST_MONTH);
        contribute(household.owner(), goal, "-500", TODAY);

        assertThat(summary(household.owner(), NOW).stringField("goalsSetAside"))
                .isEqualTo("110.0000");
    }

    @Test
    void a_paused_plan_with_a_withdrawal_counts_nothing() {
        Household household = seedHousehold();
        UUID goal =
                create(
                        household.owner(),
                        "{\"name\":\"Fund\",\"targetAmount\":\"6000\","
                                + "\"startingAmount\":\"2000\",\"monthlyContribution\":\"0\"}");
        contribute(household.owner(), goal, "-500", TODAY);

        assertThat(summary(household.owner(), NOW).stringField("goalsSetAside"))
                .isEqualTo("0.0000");
    }

    @Test
    void a_goal_that_began_the_month_reached_counts_only_what_was_recorded() {
        Household household = seedHousehold();
        UUID goal =
                create(
                        household.owner(),
                        "{\"name\":\"Fund\",\"targetAmount\":\"100\",\"monthlyContribution\":\"110\"}");
        contribute(household.owner(), goal, "100", LAST_MONTH);
        contribute(household.owner(), goal, "25", TODAY);

        assertThat(summary(household.owner(), NOW).stringField("goalsSetAside"))
                .isEqualTo("25.0000");
    }

    @Test
    void the_starting_amount_is_never_counted_in_any_month() {
        Household household = seedHousehold();
        create(
                household.owner(),
                "{\"name\":\"Fund\",\"targetAmount\":\"6000\",\"startingAmount\":\"2000\"}");

        assertThat(summary(household.owner(), NOW).stringField("goalsSetAside"))
                .isEqualTo("0.0000");
        assertThat(summary(household.owner(), NOW.minusMonths(1)).stringField("goalsSetAside"))
                .isEqualTo("0.0000");
    }

    @Test
    void archiving_a_goal_takes_it_out_of_the_month_and_left_rises_by_its_share() {
        Household household = seedHousehold();
        createIncome(household.owner(), "2400.00");
        UUID goal = japan(household.owner());
        contribute(household.owner(), goal, "200", TODAY);
        assertThat(summary(household.owner(), NOW).stringField("left")).isEqualTo("2200.0000");

        household.owner().post("/api/goals/" + goal + "/archive", null);

        ApiResponse after = summary(household.owner(), NOW);
        assertThat(after.stringField("goalsSetAside")).isEqualTo("0.0000");
        assertThat(after.numberField("goalsCounted")).isZero();
        assertThat(after.stringField("left")).isEqualTo("2400.0000");
    }

    @Test
    void a_goal_in_another_currency_is_not_added_to_a_base_currency_figure() {
        Household household = seedHousehold();
        create(
                household.owner(),
                "{\"name\":\"Lisbon\",\"targetAmount\":\"2000\",\"currency\":\"EUR\","
                        + "\"monthlyContribution\":\"150\"}");

        ApiResponse summary = summary(household.owner(), NOW);

        assertThat(summary.stringField("goalsSetAside")).isEqualTo("0.0000");
        assertThat(summary.stringField("currency")).isEqualTo("GBP");
    }

    /**
     * Decision 4's whole reason to exist: raising the plan from next month rewrites nothing now.
     */
    @Test
    void a_plan_step_set_for_next_month_leaves_this_months_left_unmoved() {
        Household household = seedHousehold();
        UUID goal = japan(household.owner());
        long version = household.owner().get("/api/goals/" + goal).numberField("version");

        household
                .owner()
                .put(
                        "/api/goals/" + goal + "/contribution-plan/" + NOW.plusMonths(1),
                        "{\"monthlyAmount\":\"165\",\"version\":%d}".formatted(version));

        assertThat(summary(household.owner(), NOW).stringField("goalsSetAside"))
                .isEqualTo("110.0000");
        assertThat(summary(household.owner(), NOW.plusMonths(1)).stringField("goalsSetAside"))
                .isEqualTo("165.0000");
    }

    @Test
    void every_unarchived_base_currency_goal_is_summed() {
        Household household = seedHousehold();
        japan(household.owner());
        UUID fund =
                create(
                        household.owner(),
                        "{\"name\":\"Fund\",\"targetAmount\":\"6000\",\"monthlyContribution\":\"200\"}");
        contribute(household.owner(), fund, "250", TODAY);

        ApiResponse summary = summary(household.owner(), NOW);

        assertThat(summary.stringField("goalsSetAside")).isEqualTo("360.0000");
        assertThat(summary.numberField("goalsCounted")).isEqualTo(2L);
    }

    @Test
    void the_debt_plans_extra_is_reported_beside_left_and_not_subtracted() {
        Household household = seedHousehold();
        createIncome(household.owner(), "2400.00");
        createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);
        long version = household.owner().get("/api/debt-plan").numberField("version");
        household
                .owner()
                .put(
                        "/api/debt-plan",
                        "{\"monthlyAmount\":\"500.00\",\"version\":%d}".formatted(version));
        japan(household.owner());

        ApiResponse summary = summary(household.owner(), NOW);

        assertThat(summary.stringField("debtPlanExtra")).isEqualTo("382.0000");
        // 2,400 − 118 (the minimum) − 110 (the goal). The 382 extra is NOT taken off as well.
        assertThat(summary.stringField("left")).isEqualTo("2172.0000");
        assertThat(summary.body()).contains("\"debtPlanExtraExceedsLeft\":false");
    }

    @Test
    void the_page_is_told_when_the_debt_plans_extra_exceeds_what_is_left() {
        Household household = seedHousehold();
        createIncome(household.owner(), "400.00");
        createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);
        long version = household.owner().get("/api/debt-plan").numberField("version");
        household
                .owner()
                .put(
                        "/api/debt-plan",
                        "{\"monthlyAmount\":\"500.00\",\"version\":%d}".formatted(version));
        japan(household.owner());

        ApiResponse summary = summary(household.owner(), NOW);

        // 400 − 118 − 110 = 172 left, against an extra of 382.
        assertThat(summary.stringField("left")).isEqualTo("172.0000");
        assertThat(summary.body()).contains("\"debtPlanExtraExceedsLeft\":true");
    }

    @Test
    void there_is_no_extra_without_a_plan() {
        Household household = seedHousehold();
        createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);

        ApiResponse summary = summary(household.owner(), NOW);

        assertThat(summary.body())
                .contains("\"debtPlanExtra\":null", "\"debtPlanExtraExceedsLeft\":false");
    }

    @Test
    void a_viewer_reads_the_fifth_term_too() {
        Household household = seedHousehold();
        japan(household.owner());

        assertThat(summary(household.viewer(), NOW).stringField("goalsSetAside"))
                .isEqualTo("110.0000");
    }

    // ------------------------------------------------------------------------------ helpers

    private static ApiResponse summary(ApiClient client, YearMonth period) {
        ApiResponse response = client.get("/api/periods/" + period + "/summary");
        assertThat(response.status()).isEqualTo(200);
        return response;
    }

    private UUID japan(ApiClient client) {
        return create(
                client,
                "{\"name\":\"Japan\",\"targetAmount\":\"3500\",\"monthlyContribution\":\"110\"}");
    }

    private UUID create(ApiClient client, String body) {
        ApiResponse response = client.post("/api/goals", body);
        if (response.status() != 201) {
            throw new AssertionError("could not create a goal: " + response.body());
        }
        return UUID.fromString(response.stringField("id"));
    }

    private static void contribute(ApiClient client, UUID goal, String amount, LocalDate on) {
        ApiResponse response =
                client.post(
                        "/api/goals/" + goal + "/contributions",
                        "{\"amount\":\"%s\",\"date\":\"%s\"}".formatted(amount, on));
        if (response.status() != 201) {
            throw new AssertionError("could not record a contribution: " + response.body());
        }
    }

    private void createIncome(ApiClient client, String amount) {
        ApiResponse response =
                client.post(
                        "/api/income",
                        """
                        {"name":"Salary","amount":"%s","schedule":{"kind":"MONTHLY","dayOfMonth":1}}
                        """
                                .formatted(amount));
        if (response.status() != 201) {
            throw new AssertionError("could not create an income source: " + response.body());
        }
    }
}
