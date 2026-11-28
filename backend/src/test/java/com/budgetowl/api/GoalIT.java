package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Goals, their plans and their contributions, over real HTTP against real PostgreSQL.
 *
 * <p>The arithmetic is proved without a database in {@code GoalProjectionTest}. What is proved here
 * is that it reaches the wire with every digit intact, that an absent field and a {@code null} are
 * different requests, that a preview is exactly what saving then reports, that the ledger is never
 * touched, and that authorization holds on every route under both transports.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GoalIT extends ApiTestBase {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final YearMonth NOW = YearMonth.from(TODAY);

    // ------------------------------------------------------------------------------- create

    @Test
    void a_goal_needs_nothing_but_a_name_and_a_target() {
        Household household = seedHousehold();

        ApiResponse created =
                household
                        .owner()
                        .post(
                                "/api/goals",
                                "{\"name\":\"Emergency fund\",\"targetAmount\":\"6000\"}");

        assertThat(created.status()).isEqualTo(201);
        String id = created.stringField("id");
        // The Location names the id and never the name, which is the most disclosing field here.
        assertThat(created.header("location")).contains("/api/goals/" + id);
        assertThat(created.stringField("currency")).isEqualTo("GBP");
        assertThat(created.stringField("targetAmount")).isEqualTo("6000.0000");
        assertThat(created.stringField("saved")).isEqualTo("0.0000");
        assertThat(created.stringField("monthlyContribution")).isEqualTo("0.0000");
        assertThat(created.stringField("projectedReason")).isEqualTo("no-contribution");
        assertThat(created.stringField("requiredReason")).isEqualTo("no-target-month");
        assertThat(created.body()).contains("\"targetMonth\":null", "\"accountId\":null");
    }

    @Test
    void a_monthly_contribution_on_create_is_the_plans_first_step_at_the_current_month() {
        Household household = seedHousehold();

        UUID goal =
                createGoal(
                        household.owner(),
                        """
                        {"name":"Japan","targetAmount":"3500.00","targetMonth":"%s",
                         "startingAmount":"890.00","monthlyContribution":"110.00"}
                        """
                                .formatted(NOW.plusMonths(15)));

        ApiResponse plan = household.owner().get("/api/goals/" + goal + "/contribution-plan");
        assertThat(plan.numberField("totalElements")).isEqualTo(1L);
        assertThat(plan.stringField("fromPeriod")).isEqualTo(NOW.toString());
        assertThat(plan.stringField("monthlyAmount")).isEqualTo("110.0000");

        ApiResponse read = household.owner().get("/api/goals/" + goal);
        assertThat(read.stringField("saved")).isEqualTo("890.0000");
        assertThat(read.stringField("remaining")).isEqualTo("2610.0000");
        // (3,500 − 890) ÷ 16, rounded UP at the currency's own scale: two places, not four.
        assertThat(read.stringField("requiredMonthly")).isEqualTo("163.13");
        assertThat(read.stringField("planned")).isEqualTo("110.0000");
        assertThat(read.stringField("stillToCome")).isEqualTo("110.0000");
        assertThat(read.stringField("period")).isEqualTo(NOW.toString());
        // The starting amount is what was already saved; it is never this month's contribution.
        assertThat(read.stringField("recorded")).isEqualTo("0.0000");
    }

    @Test
    void amounts_keep_every_digit_across_the_wire() {
        Household household = seedHousehold();
        UUID goal =
                createGoal(
                        household.owner(),
                        "{\"name\":\"Fund\",\"targetAmount\":\"6000.12\",\"startingAmount\":\"0.01\"}");

        ApiResponse contributed = contribute(household.owner(), goal, "-12.34", TODAY);

        assertThat(contributed.status()).isEqualTo(201);
        assertThat(contributed.stringField("amount")).isEqualTo("-12.3400");
        ApiResponse read = household.owner().get("/api/goals/" + goal);
        assertThat(read.body())
                .contains("\"targetAmount\":\"6000.1200\"", "\"saved\":\"-12.3300\"");
    }

    @Test
    void a_json_number_where_an_amount_belongs_is_refused_and_stores_nothing() {
        Household household = seedHousehold();

        ApiResponse numeric =
                household.owner().post("/api/goals", "{\"name\":\"Fund\",\"targetAmount\":6000}");

        assertThat(numeric.status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goals", Long.class)).isZero();
        UUID goal = createGoal(household.owner(), "{\"name\":\"Fund\",\"targetAmount\":\"6000\"}");
        ApiResponse numericContribution =
                household
                        .owner()
                        .post(
                                "/api/goals/" + goal + "/contributions",
                                "{\"amount\":110.00,\"date\":\"" + TODAY + "\"}");
        assertThat(numericContribution.status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goal_contributions", Long.class))
                .isZero();
    }

    @Test
    void invalid_input_is_a_400_naming_the_field() {
        Household household = seedHousehold();

        ApiResponse blank =
                household.owner().post("/api/goals", "{\"name\":\"   \",\"targetAmount\":\"10\"}");
        ApiResponse ideographic =
                household.owner().post("/api/goals", "{\"name\":\"　\",\"targetAmount\":\"10\"}");
        ApiResponse malformed =
                household
                        .owner()
                        .post("/api/goals", "{\"name\":\"Fund\",\"targetAmount\":\"ten\"}");
        ApiResponse zero =
                household.owner().post("/api/goals", "{\"name\":\"Fund\",\"targetAmount\":\"0\"}");

        assertThat(blank.status()).isEqualTo(400);
        assertThat(blank.body()).contains("\"field\":\"name\"");
        assertThat(ideographic.status())
                .as("an ideographic space is blank too, and must not reach the entity as a 500")
                .isEqualTo(400);
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.body()).contains("\"field\":\"targetAmount\"");
        assertThat(zero.status()).isEqualTo(400);
        assertThat(zero.body()).contains("\"field\":\"targetAmount\"");
        // The rejected value is never echoed: it is an amount.
        assertThat(malformed.body()).doesNotContain("ten");
    }

    @Test
    void an_amount_more_precise_than_the_goals_currency_is_refused() {
        Household household = seedHousehold();

        ApiResponse yen =
                household
                        .owner()
                        .post(
                                "/api/goals",
                                "{\"name\":\"Tokyo\",\"targetAmount\":\"350000.50\",\"currency\":\"JPY\"}");
        ApiResponse pence =
                household
                        .owner()
                        .post("/api/goals", "{\"name\":\"Fund\",\"targetAmount\":\"10.001\"}");
        UUID whole =
                createGoal(
                        household.owner(),
                        "{\"name\":\"Tokyo\",\"targetAmount\":\"350000\",\"currency\":\"JPY\"}");

        assertThat(yen.status()).isEqualTo(400);
        assertThat(yen.body()).contains("\"field\":\"targetAmount\"");
        assertThat(pence.status()).isEqualTo(400);
        assertThat(household.owner().get("/api/goals/" + whole).stringField("currency"))
                .isEqualTo("JPY");
        assertThat(contribute(household.owner(), whole, "100.5", TODAY).status()).isEqualTo(400);
    }

    @Test
    void a_currency_is_refused_alongside_an_account_which_owns_that_fact() {
        Household household = seedHousehold();
        UUID euros = createAccountIn(household.owner(), "Euro savings", "EUR");

        ApiResponse both =
                household
                        .owner()
                        .post(
                                "/api/goals",
                                """
                                {"name":"Lisbon","targetAmount":"2000","currency":"EUR",
                                 "accountId":"%s"}
                                """
                                        .formatted(euros));
        UUID kept =
                createGoal(
                        household.owner(),
                        "{\"name\":\"Lisbon\",\"targetAmount\":\"2000\",\"accountId\":\"%s\"}"
                                .formatted(euros));

        assertThat(both.status()).isEqualTo(400);
        assertThat(both.body()).contains("\"field\":\"currency\"");
        assertThat(household.owner().get("/api/goals/" + kept).stringField("currency"))
                .isEqualTo("EUR");
    }

    @Test
    void linking_an_account_in_another_currency_is_a_currency_mismatch() {
        Household household = seedHousehold();
        UUID goal = createGoal(household.owner(), "{\"name\":\"Fund\",\"targetAmount\":\"6000\"}");
        UUID euros = createAccountIn(household.owner(), "Euro savings", "EUR");

        ApiResponse refused =
                household
                        .owner()
                        .patch(
                                "/api/goals/" + goal,
                                "{\"accountId\":\"%s\",\"version\":%d}"
                                        .formatted(euros, version(household.owner(), goal)));

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.stringField("code")).isEqualTo("goal-currency-mismatch");
        assertThat(household.owner().get("/api/goals/" + goal).body())
                .contains("\"accountId\":null");
    }

    @Test
    void a_currency_is_not_a_patchable_field() {
        Household household = seedHousehold();
        UUID goal = createGoal(household.owner(), "{\"name\":\"Fund\",\"targetAmount\":\"6000\"}");

        ApiResponse refused =
                household
                        .owner()
                        .patch(
                                "/api/goals/" + goal,
                                "{\"currency\":\"EUR\",\"version\":%d}"
                                        .formatted(version(household.owner(), goal)));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(household.owner().get("/api/goals/" + goal).stringField("currency"))
                .isEqualTo("GBP");
    }

    @Test
    void a_household_id_in_a_body_is_refused_rather_than_ignored() {
        Household household = seedHousehold();

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/goals",
                                "{\"name\":\"Fund\",\"targetAmount\":\"6000\",\"householdId\":\""
                                        + UUID.randomUUID()
                                        + "\"}");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goals", Long.class)).isZero();
    }

    @Test
    void names_are_unique_among_unarchived_goals_case_insensitively() {
        Household household = seedHousehold();
        UUID first =
                createGoal(household.owner(), "{\"name\":\"Holiday\",\"targetAmount\":\"10\"}");

        ApiResponse again =
                household
                        .owner()
                        .post("/api/goals", "{\"name\":\"holiday\",\"targetAmount\":\"20\"}");
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.stringField("code")).isEqualTo("goal-name-taken");

        // A retired Holiday does not stop next year's — and then restoring the old one collides.
        household.owner().post("/api/goals/" + first + "/archive", null);
        createGoal(household.owner(), "{\"name\":\"Holiday\",\"targetAmount\":\"20\"}");
        ApiResponse restored = household.owner().post("/api/goals/" + first + "/unarchive", null);
        assertThat(restored.status()).isEqualTo(409);
        assertThat(restored.stringField("code")).isEqualTo("goal-name-taken");
    }

    // --------------------------------------------------------------------------------- list

    @Test
    void the_list_is_ordered_by_target_month_with_none_last_then_name_then_id() {
        Household household = seedHousehold();
        createGoal(household.owner(), "{\"name\":\"zebra\",\"targetAmount\":\"10\"}");
        createGoal(
                household.owner(),
                "{\"name\":\"Later\",\"targetAmount\":\"10\",\"targetMonth\":\"2031-01\"}");
        createGoal(household.owner(), "{\"name\":\"Apple\",\"targetAmount\":\"10\"}");
        createGoal(
                household.owner(),
                "{\"name\":\"Sooner\",\"targetAmount\":\"10\",\"targetMonth\":\"2029-06\"}");
        createGoal(
                household.owner(),
                "{\"name\":\"also sooner\",\"targetAmount\":\"10\",\"targetMonth\":\"2029-06\"}");

        List<String> names = names(household.owner().get("/api/goals").body());

        assertThat(names).containsExactly("also sooner", "Sooner", "Later", "Apple", "zebra");
    }

    @Test
    void archived_goals_are_listed_only_when_asked_for() {
        Household household = seedHousehold();
        UUID goal = createGoal(household.owner(), "{\"name\":\"Old\",\"targetAmount\":\"10\"}");
        household.owner().post("/api/goals/" + goal + "/archive", null);

        assertThat(household.owner().get("/api/goals").numberField("totalElements")).isZero();
        ApiResponse all = household.owner().get("/api/goals?includeArchived=true");
        assertThat(all.numberField("totalElements")).isEqualTo(1L);
        assertThat(all.body()).contains("\"archived\":true");
    }

    // -------------------------------------------------------------------------------- patch

    @Test
    void an_absent_target_month_is_left_alone_and_a_null_one_clears_it() {
        Household household = seedHousehold();
        UUID goal =
                createGoal(
                        household.owner(),
                        "{\"name\":\"Japan\",\"targetAmount\":\"3500\",\"targetMonth\":\"2027-11\"}");

        ApiResponse renamed =
                household
                        .owner()
                        .patch(
                                "/api/goals/" + goal,
                                "{\"name\":\"Japan trip\",\"version\":%d}"
                                        .formatted(version(household.owner(), goal)));
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(renamed.stringField("targetMonth")).isEqualTo("2027-11");

        ApiResponse cleared =
                household
                        .owner()
                        .patch(
                                "/api/goals/" + goal,
                                "{\"targetMonth\":null,\"version\":%d}"
                                        .formatted(version(household.owner(), goal)));
        assertThat(cleared.status()).isEqualTo(200);
        assertThat(cleared.body()).contains("\"targetMonth\":null");
        assertThat(cleared.stringField("name")).isEqualTo("Japan trip");
    }

    @Test
    void an_absent_account_is_left_alone_and_a_null_one_detaches_it() {
        Household household = seedHousehold();
        UUID savings = createAccount(household.owner(), "Savings", null);
        UUID goal =
                createGoal(
                        household.owner(),
                        "{\"name\":\"Fund\",\"targetAmount\":\"6000\",\"accountId\":\"%s\"}"
                                .formatted(savings));

        ApiResponse retargeted =
                household
                        .owner()
                        .patch(
                                "/api/goals/" + goal,
                                "{\"targetAmount\":\"7000\",\"version\":%d}"
                                        .formatted(version(household.owner(), goal)));
        assertThat(retargeted.stringField("accountId")).isEqualTo(savings.toString());

        ApiResponse detached =
                household
                        .owner()
                        .patch(
                                "/api/goals/" + goal,
                                "{\"accountId\":null,\"version\":%d}"
                                        .formatted(version(household.owner(), goal)));
        assertThat(detached.body()).contains("\"accountId\":null");
        assertThat(detached.stringField("targetAmount")).isEqualTo("7000.0000");
    }

    @Test
    void a_stale_version_is_refused_rather_than_overwriting_somebody_elses_edit() {
        Household household = seedHousehold();
        UUID goal = createGoal(household.owner(), "{\"name\":\"Fund\",\"targetAmount\":\"6000\"}");
        long stale = version(household.owner(), goal);
        household
                .owner()
                .patch(
                        "/api/goals/" + goal,
                        "{\"name\":\"Rainy day\",\"version\":%d}".formatted(stale));

        ApiResponse second =
                household
                        .member()
                        .patch(
                                "/api/goals/" + goal,
                                "{\"name\":\"Buffer\",\"version\":%d}".formatted(stale));

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.stringField("code")).isEqualTo("stale-version");
    }

    @Test
    void a_patch_reports_the_version_it_wrote_so_the_next_patch_is_not_refused() {
        Household household = seedHousehold();
        UUID goal = createGoal(household.owner(), "{\"name\":\"Fund\",\"targetAmount\":\"6000\"}");

        ApiResponse first =
                household
                        .owner()
                        .patch(
                                "/api/goals/" + goal,
                                "{\"name\":\"A\",\"version\":%d}"
                                        .formatted(version(household.owner(), goal)));
        ApiResponse second =
                household
                        .owner()
                        .patch(
                                "/api/goals/" + goal,
                                "{\"name\":\"B\",\"version\":%d}"
                                        .formatted(first.numberField("version")));

        assertThat(second.status()).isEqualTo(200);
    }

    // --------------------------------------------------------------------------------- plan

    @Test
    void setting_the_plan_replaces_later_steps_and_sending_it_twice_changes_nothing_more() {
        Household household = seedHousehold();
        UUID goal =
                createGoal(
                        household.owner(),
                        "{\"name\":\"Japan\",\"targetAmount\":\"3500\",\"monthlyContribution\":\"110\"}");
        setPlan(household.owner(), goal, NOW.plusMonths(3), "200").status();

        ApiResponse raised = setPlan(household.owner(), goal, NOW.plusMonths(1), "165");
        ApiResponse again = setPlan(household.owner(), goal, NOW.plusMonths(1), "165");

        assertThat(raised.status()).isEqualTo(200);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.numberField("version"))
                .as("nothing changed, so nothing was written")
                .isEqualTo(raised.numberField("version"));
        ApiResponse plan = household.owner().get("/api/goals/" + goal + "/contribution-plan");
        assertThat(plan.numberField("totalElements")).isEqualTo(2L);
        assertThat(plan.body())
                .contains("\"fromPeriod\":\"" + NOW + "\"", "\"fromPeriod\":\"" + NOW.plusMonths(1))
                .doesNotContain(NOW.plusMonths(3).toString())
                .doesNotContain("200.0000");
    }

    @Test
    void the_plan_cannot_change_a_past_month() {
        Household household = seedHousehold();
        UUID goal = createGoal(household.owner(), "{\"name\":\"Japan\",\"targetAmount\":\"3500\"}");

        ApiResponse refused = setPlan(household.owner(), goal, NOW.minusMonths(1), "110");

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.stringField("code")).isEqualTo("goal-plan-period-past");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goal_contribution_steps", Long.class))
                .isZero();
    }

    @Test
    void a_plan_change_bumps_the_goals_version_so_a_stale_one_is_refused() {
        Household household = seedHousehold();
        UUID goal = createGoal(household.owner(), "{\"name\":\"Japan\",\"targetAmount\":\"3500\"}");
        long before = version(household.owner(), goal);

        setPlan(household.owner(), goal, NOW, "110");
        ApiResponse stale =
                household
                        .member()
                        .put(
                                "/api/goals/" + goal + "/contribution-plan/" + NOW,
                                "{\"monthlyAmount\":\"120\",\"version\":%d}".formatted(before));

        assertThat(version(household.owner(), goal)).isGreaterThan(before);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.stringField("code")).isEqualTo("stale-version");
    }

    @Test
    void a_malformed_plan_month_is_a_400_and_not_a_500() {
        Household household = seedHousehold();
        UUID goal = createGoal(household.owner(), "{\"name\":\"Japan\",\"targetAmount\":\"3500\"}");

        ApiResponse refused =
                household
                        .owner()
                        .put(
                                "/api/goals/" + goal + "/contribution-plan/not-a-month",
                                "{\"monthlyAmount\":\"110\",\"version\":0}");

        assertThat(refused.status()).isEqualTo(400);
    }

    // ------------------------------------------------------------------------ contributions

    @Test
    void a_contribution_writes_no_transaction_and_moves_no_account() {
        Household household = seedHousehold();
        UUID savings = createAccount(household.owner(), "Savings", null);
        UUID goal =
                createGoal(
                        household.owner(),
                        "{\"name\":\"Fund\",\"targetAmount\":\"6000\",\"accountId\":\"%s\"}"
                                .formatted(savings));
        String balanceBefore = household.owner().get("/api/accounts/" + savings).body();

        assertThat(contribute(household.owner(), goal, "500", TODAY).status()).isEqualTo(201);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Long.class)).isZero();
        assertThat(household.owner().get("/api/accounts/" + savings).body())
                .isEqualTo(balanceBefore);
        assertThat(household.owner().get("/api/goals/" + goal).stringField("saved"))
                .isEqualTo("500.0000");
    }

    @Test
    void a_contribution_may_be_dated_tomorrow_and_no_later() {
        Household household = seedHousehold();
        UUID goal = createGoal(household.owner(), "{\"name\":\"Fund\",\"targetAmount\":\"6000\"}");

        assertThat(contribute(household.owner(), goal, "10", TODAY.plusDays(1)).status())
                .isEqualTo(201);
        ApiResponse refused = contribute(household.owner(), goal, "10", TODAY.plusDays(2));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("\"field\":\"date\"");
    }

    @Test
    void a_zero_contribution_is_refused_and_a_negative_one_is_a_withdrawal() {
        Household household = seedHousehold();
        UUID goal =
                createGoal(
                        household.owner(),
                        "{\"name\":\"Fund\",\"targetAmount\":\"6000\",\"startingAmount\":\"2000\"}");

        ApiResponse zero = contribute(household.owner(), goal, "0.00", TODAY);
        ApiResponse withdrawal = contribute(household.owner(), goal, "-2500", TODAY);

        assertThat(zero.status()).isEqualTo(400);
        assertThat(withdrawal.status()).isEqualTo(201);
        // Not clamped: an emergency fund that paid for more than it held reads negative.
        assertThat(household.owner().get("/api/goals/" + goal).stringField("saved"))
                .isEqualTo("-500.0000");
    }

    @Test
    void the_history_is_newest_first_and_two_rows_on_one_day_keep_the_order_they_were_written() {
        Household household = seedHousehold();
        UUID goal = createGoal(household.owner(), "{\"name\":\"Fund\",\"targetAmount\":\"6000\"}");
        contribute(household.owner(), goal, "1", TODAY.minusDays(3));
        contribute(household.owner(), goal, "2", TODAY);
        contribute(household.owner(), goal, "3", TODAY);

        ApiResponse history = household.owner().get("/api/goals/" + goal + "/contributions");

        assertThat(amounts(history.body())).containsExactly("3.0000", "2.0000", "1.0000");
    }

    @Test
    void a_contribution_can_be_corrected_with_a_three_way_note() {
        Household household = seedHousehold();
        UUID goal = createGoal(household.owner(), "{\"name\":\"Fund\",\"targetAmount\":\"6000\"}");
        ApiResponse created =
                household
                        .owner()
                        .post(
                                "/api/goals/" + goal + "/contributions",
                                "{\"amount\":\"10\",\"date\":\"%s\",\"note\":\"birthday\"}"
                                        .formatted(TODAY));
        String path = "/api/goals/" + goal + "/contributions/" + created.stringField("id");

        ApiResponse amountOnly =
                household
                        .owner()
                        .patch(
                                path,
                                "{\"amount\":\"15\",\"version\":%d}"
                                        .formatted(created.numberField("version")));
        assertThat(amountOnly.stringField("note")).isEqualTo("birthday");
        ApiResponse cleared =
                household
                        .owner()
                        .patch(
                                path,
                                "{\"note\":null,\"version\":%d}"
                                        .formatted(amountOnly.numberField("version")));
        assertThat(cleared.body()).contains("\"note\":null");
        assertThat(cleared.stringField("amount")).isEqualTo("15.0000");
    }

    @Test
    void a_contribution_of_the_right_household_but_another_goal_is_not_found() {
        Household household = seedHousehold();
        UUID mine = createGoal(household.owner(), "{\"name\":\"A\",\"targetAmount\":\"10\"}");
        UUID other = createGoal(household.owner(), "{\"name\":\"B\",\"targetAmount\":\"10\"}");
        String contribution = contribute(household.owner(), other, "5", TODAY).stringField("id");

        ApiResponse patched =
                household
                        .owner()
                        .patch(
                                "/api/goals/" + mine + "/contributions/" + contribution,
                                "{\"amount\":\"9\",\"version\":0}");
        ApiResponse deleted =
                household.owner().delete("/api/goals/" + mine + "/contributions/" + contribution);

        assertThat(patched.status()).isEqualTo(404);
        assertThat(deleted.status()).isEqualTo(404);
        assertThat(household.owner().get("/api/goals/" + other).stringField("saved"))
                .isEqualTo("5.0000");
    }

    @Test
    void a_transaction_is_evidence_for_at_most_one_contribution() {
        Household household = seedHousehold();
        UUID savings = createAccount(household.owner(), "Savings", null);
        UUID transaction =
                createTransaction(household.owner(), savings, TODAY.toString(), "250.00", null);
        UUID first = createGoal(household.owner(), "{\"name\":\"A\",\"targetAmount\":\"10\"}");
        UUID second = createGoal(household.owner(), "{\"name\":\"B\",\"targetAmount\":\"10\"}");

        ApiResponse linked = contributeWith(household.owner(), first, transaction);
        ApiResponse again = contributeWith(household.owner(), second, transaction);

        assertThat(linked.status()).isEqualTo(201);
        assertThat(linked.stringField("transactionId")).isEqualTo(transaction.toString());
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.stringField("code")).isEqualTo("conflict");
    }

    @Test
    void deleting_the_linked_transaction_leaves_the_contribution_and_saved_standing() {
        Household household = seedHousehold();
        UUID savings = createAccount(household.owner(), "Savings", null);
        UUID transaction =
                createTransaction(household.owner(), savings, TODAY.toString(), "250.00", null);
        UUID goal = createGoal(household.owner(), "{\"name\":\"Fund\",\"targetAmount\":\"6000\"}");
        contributeWith(household.owner(), goal, transaction);

        assertThat(household.owner().delete("/api/transactions/" + transaction).status())
                .isEqualTo(204);

        ApiResponse history = household.owner().get("/api/goals/" + goal + "/contributions");
        assertThat(history.numberField("totalElements")).isEqualTo(1L);
        assertThat(history.body()).contains("\"transactionId\":null");
        assertThat(household.owner().get("/api/goals/" + goal).stringField("saved"))
                .isEqualTo("250.0000");
    }

    @Test
    void deleting_a_goal_takes_its_plan_and_contributions_and_no_transaction() {
        Household household = seedHousehold();
        UUID savings = createAccount(household.owner(), "Savings", null);
        UUID transaction =
                createTransaction(household.owner(), savings, TODAY.toString(), "250.00", null);
        UUID goal =
                createGoal(
                        household.owner(),
                        "{\"name\":\"Fund\",\"targetAmount\":\"6000\",\"monthlyContribution\":\"100\"}");
        contributeWith(household.owner(), goal, transaction);
        contribute(household.owner(), goal, "10", TODAY);

        assertThat(household.owner().delete("/api/goals/" + goal).status()).isEqualTo(204);

        assertThat(household.owner().get("/api/goals/" + goal).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goal_contributions", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goal_contribution_steps", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void an_archived_goal_takes_no_contribution_and_no_plan_change_and_keeps_its_history() {
        Household household = seedHousehold();
        UUID goal = createGoal(household.owner(), "{\"name\":\"Fund\",\"targetAmount\":\"6000\"}");
        contribute(household.owner(), goal, "10", TODAY);
        household.owner().post("/api/goals/" + goal + "/archive", null);

        ApiResponse contribution = contribute(household.owner(), goal, "10", TODAY);
        ApiResponse plan = setPlan(household.owner(), goal, NOW, "50");

        assertThat(contribution.status()).isEqualTo(409);
        assertThat(contribution.stringField("code")).isEqualTo("goal-archived");
        assertThat(plan.status()).isEqualTo(409);
        assertThat(plan.stringField("code")).isEqualTo("goal-archived");
        assertThat(
                        household
                                .owner()
                                .get("/api/goals/" + goal + "/contributions")
                                .numberField("totalElements"))
                .isEqualTo(1L);
    }

    @Test
    void a_reached_goal_still_takes_contributions_and_reads_over_its_target() {
        Household household = seedHousehold();
        UUID goal =
                createGoal(
                        household.owner(),
                        "{\"name\":\"Fund\",\"targetAmount\":\"100\",\"startingAmount\":\"100\"}");

        assertThat(contribute(household.owner(), goal, "25", TODAY).status()).isEqualTo(201);

        ApiResponse read = household.owner().get("/api/goals/" + goal);
        assertThat(read.body()).contains("\"reached\":true");
        assertThat(read.stringField("remaining")).isEqualTo("-25.0000");
        assertThat(read.stringField("projectedReason")).isEqualTo("reached");
    }

    // ------------------------------------------------------------------------------ previews

    /**
     * The debt-preview lesson: a preview built by a second path drifts. Both previews here are the
     * same projection code, so what one shows is exactly what saving then reports.
     */
    @Test
    void the_goal_preview_is_exactly_what_setting_that_plan_then_reports() {
        Household household = seedHousehold();
        UUID goal =
                createGoal(
                        household.owner(),
                        """
                        {"name":"Japan","targetAmount":"3500","targetMonth":"%s",
                         "monthlyContribution":"110"}
                        """
                                .formatted(NOW.plusMonths(15)));
        contribute(household.owner(), goal, "890", TODAY.withDayOfMonth(1).minusDays(1));
        contribute(household.owner(), goal, "40", TODAY);

        ApiResponse preview =
                household
                        .owner()
                        .post(
                                "/api/goals/" + goal + "/projection-preview",
                                "{\"monthlyAmount\":\"165\"}");
        ApiResponse saved = setPlan(household.owner(), goal, NOW, "165");

        assertThat(preview.status()).isEqualTo(200);
        assertThat(object(preview.body(), "projection"))
                .isEqualTo(object(saved.body(), "projection"));
        assertThat(object(preview.body(), "thisPeriod"))
                .isEqualTo(object(saved.body(), "thisPeriod"));
        assertThat(preview.stringField("monthlyContribution"))
                .isEqualTo(saved.stringField("monthlyContribution"))
                .isEqualTo("165.0000");
    }

    @Test
    void the_goal_preview_writes_nothing() {
        Household household = seedHousehold();
        UUID goal =
                createGoal(
                        household.owner(),
                        "{\"name\":\"Japan\",\"targetAmount\":\"3500\",\"monthlyContribution\":\"110\"}");
        long version = version(household.owner(), goal);

        household
                .owner()
                .post(
                        "/api/goals/" + goal + "/projection-preview",
                        "{\"monthlyAmount\":\"165\",\"targetAmount\":\"9999\",\"targetMonth\":null}");

        ApiResponse read = household.owner().get("/api/goals/" + goal);
        assertThat(read.numberField("version")).isEqualTo(version);
        assertThat(read.stringField("monthlyContribution")).isEqualTo("110.0000");
        assertThat(read.stringField("targetAmount")).isEqualTo("3500.0000");
    }

    @Test
    void the_draft_preview_is_exactly_what_creating_that_goal_then_reports() {
        Household household = seedHousehold();
        String month = NOW.plusMonths(15).toString();

        ApiResponse preview =
                household
                        .owner()
                        .post(
                                "/api/goals/projection-preview",
                                """
                                {"targetAmount":"3500","startingAmount":"890",
                                 "targetMonth":"%s","monthlyAmount":"110"}
                                """
                                        .formatted(month));
        UUID goal =
                createGoal(
                        household.owner(),
                        """
                        {"name":"Japan","targetAmount":"3500","startingAmount":"890",
                         "targetMonth":"%s","monthlyContribution":"110"}
                        """
                                .formatted(month));
        ApiResponse created = household.owner().get("/api/goals/" + goal);

        assertThat(preview.status()).isEqualTo(200);
        assertThat(preview.stringField("currency")).isEqualTo("GBP");
        assertThat(object(preview.body(), "projection"))
                .isEqualTo(object(created.body(), "projection"));
        assertThat(object(preview.body(), "thisPeriod"))
                .isEqualTo(object(created.body(), "thisPeriod"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goals", Long.class)).isEqualTo(1L);
    }

    // ----------------------------------------------------------------- scoping and the roles

    /** Method, path and body for each route, with {@code {id}} to be substituted. */
    record Endpoint(String method, String path, String body) {

        ApiResponse callWith(ApiClient client, UUID goalId) {
            return client.send(method, path.replace("{id}", goalId.toString()), body);
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    /** The fifteen routes of the feature doc's API table. */
    static List<Endpoint> everyRoute() {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        String now = YearMonth.now(ZoneOffset.UTC).toString();
        return List.of(
                new Endpoint("GET", "/api/goals", null),
                new Endpoint("POST", "/api/goals", "{\"name\":\"New\",\"targetAmount\":\"10\"}"),
                new Endpoint("GET", "/api/goals/{id}", null),
                new Endpoint("PATCH", "/api/goals/{id}", "{\"name\":\"Renamed\",\"version\":0}"),
                new Endpoint("DELETE", "/api/goals/{id}", null),
                new Endpoint("POST", "/api/goals/{id}/archive", null),
                new Endpoint("POST", "/api/goals/{id}/unarchive", null),
                new Endpoint("GET", "/api/goals/{id}/contribution-plan", null),
                new Endpoint(
                        "PUT",
                        "/api/goals/{id}/contribution-plan/" + now,
                        "{\"monthlyAmount\":\"165\",\"version\":0}"),
                new Endpoint("GET", "/api/goals/{id}/contributions", null),
                new Endpoint(
                        "POST",
                        "/api/goals/{id}/contributions",
                        "{\"amount\":\"110\",\"date\":\"" + today + "\"}"),
                new Endpoint(
                        "PATCH",
                        "/api/goals/{id}/contributions/" + UUID.randomUUID(),
                        "{\"amount\":\"10\",\"version\":0}"),
                new Endpoint("DELETE", "/api/goals/{id}/contributions/" + UUID.randomUUID(), null),
                // Write nothing, but they are POSTs and a VIEWER is refused them, so they belong in
                // the list every authorization test sweeps.
                new Endpoint(
                        "POST",
                        "/api/goals/{id}/projection-preview",
                        "{\"monthlyAmount\":\"165\"}"),
                new Endpoint(
                        "POST",
                        "/api/goals/projection-preview",
                        "{\"targetAmount\":\"3500\",\"monthlyAmount\":\"110\"}"));
    }

    static List<Endpoint> everyWrite() {
        return everyRoute().stream().filter(endpoint -> !"GET".equals(endpoint.method())).toList();
    }

    @Test
    void the_sweep_covers_all_fifteen_routes() {
        assertThat(everyRoute()).hasSize(15);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRoute")
    void an_unauthenticated_request_is_refused(Endpoint endpoint) {
        seedHousehold();

        ApiResponse refused = endpoint.callWith(browser(), UUID.randomUUID());

        assertThat(refused.status()).isEqualTo(401);
        assertThat(refused.stringField("code")).isEqualTo("not-authenticated");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void a_viewer_may_write_nothing(Endpoint endpoint) {
        Household household = seedHousehold();
        UUID goal = createGoal(household.owner(), "{\"name\":\"Japan\",\"targetAmount\":\"3500\"}");

        ApiResponse refused = endpoint.callWith(household.viewer(), goal);

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.stringField("code")).isEqualTo("read-only-role");
        assertThat(household.owner().get("/api/goals/" + goal).stringField("name"))
                .isEqualTo("Japan");
    }

    /**
     * The role is checked before the goal is looked up. In the other order a {@code VIEWER} sending
     * ids could tell which exist: {@code 404} for a guess, {@code 403} for a hit.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void a_viewer_gets_403_for_an_id_that_does_not_exist_too(Endpoint endpoint) {
        Household household = seedHousehold();

        ApiResponse refused = endpoint.callWith(household.viewer(), UUID.randomUUID());

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.stringField("code")).isEqualTo("read-only-role");
    }

    @Test
    void a_viewer_reads_every_goal_figure_plan_and_contribution() {
        Household household = seedHousehold();
        UUID goal =
                createGoal(
                        household.owner(),
                        "{\"name\":\"Japan\",\"targetAmount\":\"3500\",\"monthlyContribution\":\"110\"}");
        contribute(household.owner(), goal, "110", TODAY);

        assertThat(household.viewer().get("/api/goals").numberField("totalElements")).isEqualTo(1L);
        assertThat(household.viewer().get("/api/goals/" + goal).stringField("saved"))
                .isEqualTo("110.0000");
        assertThat(
                        household
                                .viewer()
                                .get("/api/goals/" + goal + "/contribution-plan")
                                .numberField("totalElements"))
                .isEqualTo(1L);
        assertThat(
                        household
                                .viewer()
                                .get("/api/goals/" + goal + "/contributions")
                                .numberField("totalElements"))
                .isEqualTo(1L);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void a_write_without_a_csrf_token_is_refused_on_the_cookie_transport(Endpoint endpoint) {
        Household household = seedHousehold();
        UUID goal = createGoal(household.owner(), "{\"name\":\"Japan\",\"targetAmount\":\"3500\"}");

        ApiResponse refused =
                endpoint.callWith(signIn(OWNER_EMAIL, OWNER_SECRET).withoutCsrfToken(), goal);

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.stringField("code")).isEqualTo("csrf-token-required");
        assertThat(household.owner().get("/api/goals/" + goal).stringField("name"))
                .isEqualTo("Japan");
    }

    @Test
    void the_bearer_transport_authorizes_identically() {
        Household household = seedHousehold();
        UUID goal = createGoal(household.owner(), "{\"name\":\"Japan\",\"targetAmount\":\"3500\"}");
        ApiClient phone = mobile(OWNER_EMAIL, OWNER_SECRET, "Ada's phone");
        ApiClient viewersPhone = mobile(VIEWER_EMAIL, VIEWER_SECRET, "Alan's phone");

        assertThat(phone.get("/api/goals/" + goal).status()).isEqualTo(200);
        assertThat(contribute(phone, goal, "110", TODAY).status())
                .as("CSRF does not apply to a bearer request; authorization still does")
                .isEqualTo(201);
        assertThat(contribute(viewersPhone, goal, "110", TODAY).status())
                .as("neither transport may skip a check the other applies (ADR-0018)")
                .isEqualTo(403);
        assertThat(viewersPhone.get("/api/goals/" + goal).status()).isEqualTo(200);
        assertThat(
                        phone.post(
                                        "/api/goals/" + goal + "/projection-preview",
                                        "{\"monthlyAmount\":\"165\"}")
                                .status())
                .isEqualTo(200);
        assertThat(
                        viewersPhone
                                .post(
                                        "/api/goals/" + goal + "/projection-preview",
                                        "{\"monthlyAmount\":\"165\"}")
                                .status())
                .isEqualTo(403);
        assertThat(phone.get("/api/goals/" + UUID.randomUUID()).status()).isEqualTo(404);
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

    // ------------------------------------------------------------------------------ helpers

    private UUID createGoal(ApiClient client, String body) {
        ApiResponse response = client.post("/api/goals", body);
        if (response.status() != 201) {
            throw new AssertionError("could not create a goal: " + response.body());
        }
        return UUID.fromString(response.stringField("id"));
    }

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

    private static long version(ApiClient client, UUID goal) {
        return client.get("/api/goals/" + goal).numberField("version");
    }

    private static ApiResponse contribute(
            ApiClient client, UUID goal, String amount, LocalDate on) {
        return client.post(
                "/api/goals/" + goal + "/contributions",
                "{\"amount\":\"%s\",\"date\":\"%s\"}".formatted(amount, on));
    }

    private static ApiResponse contributeWith(ApiClient client, UUID goal, UUID transaction) {
        return client.post(
                "/api/goals/" + goal + "/contributions",
                "{\"amount\":\"250\",\"date\":\"%s\",\"transactionId\":\"%s\"}"
                        .formatted(LocalDate.now(ZoneOffset.UTC), transaction));
    }

    private static ApiResponse setPlan(ApiClient client, UUID goal, YearMonth from, String amount) {
        return client.put(
                "/api/goals/" + goal + "/contribution-plan/" + from,
                "{\"monthlyAmount\":\"%s\",\"version\":%d}"
                        .formatted(amount, version(client, goal)));
    }

    private static List<String> names(String body) {
        return Pattern.compile("\"name\":\"([^\"]*)\"")
                .matcher(body)
                .results()
                .map(match -> match.group(1))
                .toList();
    }

    private static List<String> amounts(String body) {
        return Pattern.compile("\"amount\":\"([^\"]*)\"")
                .matcher(body)
                .results()
                .map(match -> match.group(1))
                .toList();
    }

    /** The text of one flat nested object, for comparing two responses' copies of it. */
    private static String object(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\":(\\{[^}]*\\})").matcher(body);
        if (!matcher.find()) {
            throw new AssertionError("no object '" + name + "' in: " + body);
        }
        return matcher.group(1);
    }
}
