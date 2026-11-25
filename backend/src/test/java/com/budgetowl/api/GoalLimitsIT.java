package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.goal.service.GoalService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The cost controls (docs/features/goals.md, threat model attacks 5 and 6): the caps, the page
 * envelope, and the projection horizon, as a caller sees them.
 *
 * <p>Rows past a cap are seeded with plain SQL, because creating fifty goals or two thousand
 * contributions through the API would test the API's patience rather than the cap.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GoalLimitsIT extends ApiTestBase {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @Test
    void past_the_goal_cap_a_new_goal_is_refused_and_nothing_is_written() {
        Household household = seedHousehold();
        seedGoals(householdId(), GoalService.MAXIMUM_PER_HOUSEHOLD, false);

        ApiResponse refused =
                household
                        .owner()
                        .post("/api/goals", "{\"name\":\"One too many\",\"targetAmount\":\"10\"}");

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.stringField("code")).isEqualTo("goal-limit-reached");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goals", Long.class))
                .isEqualTo((long) GoalService.MAXIMUM_PER_HOUSEHOLD);
    }

    @Test
    void the_goal_cap_counts_archived_goals_too() {
        Household household = seedHousehold();
        seedGoals(householdId(), GoalService.MAXIMUM_PER_HOUSEHOLD, true);

        ApiResponse refused =
                household
                        .owner()
                        .post("/api/goals", "{\"name\":\"One too many\",\"targetAmount\":\"10\"}");

        // Archiving one's way past the cap would leave the rows there to be projected the moment
        // anything un-archives them.
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.stringField("code")).isEqualTo("goal-limit-reached");
    }

    @Test
    void past_the_contribution_cap_a_new_contribution_is_refused() {
        Household household = seedHousehold();
        UUID goal = createGoal(household.owner(), "Fund");
        seedContributions(goal, GoalService.MAXIMUM_CONTRIBUTIONS_PER_GOAL);

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/goals/" + goal + "/contributions",
                                "{\"amount\":\"1\",\"date\":\"" + TODAY + "\"}");

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.stringField("code")).isEqualTo("goal-limit-reached");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goal_contributions", Long.class))
                .isEqualTo((long) GoalService.MAXIMUM_CONTRIBUTIONS_PER_GOAL);
    }

    @Test
    void the_goal_list_returns_a_page_and_clamps_an_oversized_one() {
        Household household = seedHousehold();
        seedGoals(householdId(), GoalService.MAXIMUM_PER_HOUSEHOLD, false);

        ApiResponse small = household.owner().get("/api/goals?size=20");
        ApiResponse last = household.owner().get("/api/goals?size=20&page=2");
        ApiResponse oversized = household.owner().get("/api/goals?size=500");

        assertThat(small.numberField("totalElements")).isEqualTo(50L);
        assertThat(small.numberField("totalPages")).isEqualTo(3L);
        assertThat(countOf(small, "\"targetAmount\"")).isEqualTo(20);
        assertThat(countOf(last, "\"targetAmount\"")).isEqualTo(10);
        assertThat(oversized.numberField("size")).isEqualTo(200L);
    }

    @Test
    void the_contribution_history_returns_a_page_and_honours_its_bounds() {
        Household household = seedHousehold();
        UUID goal = createGoal(household.owner(), "Fund");
        seedContributions(goal, 60);

        ApiResponse first = household.owner().get("/api/goals/" + goal + "/contributions");
        ApiResponse second =
                household.owner().get("/api/goals/" + goal + "/contributions?page=1&size=50");

        assertThat(first.numberField("totalElements")).isEqualTo(60L);
        assertThat(first.numberField("totalPages")).isEqualTo(2L);
        assertThat(countOf(first, "\"period\"")).isEqualTo(50);
        assertThat(countOf(second, "\"period\"")).isEqualTo(10);
    }

    /**
     * Attack 5 end to end: fifty goals, each a plan of one penny against the largest target the
     * column takes, read in one list. The walk is capped, so this answers quickly with {@code
     * beyond-horizon} rather than walking for 10^17 months per goal.
     */
    @Test
    void a_list_of_goals_that_would_take_forever_answers_quickly_with_beyond_horizon() {
        Household household = seedHousehold();
        UUID householdId = householdId();
        seedGoals(householdId, GoalService.MAXIMUM_PER_HOUSEHOLD, false);
        jdbc.update("UPDATE goals SET target_amount = 999999999999999.0000");
        jdbc.update(
                """
                INSERT INTO goal_contribution_steps (id, household_id, goal_id, from_period,
                                                     monthly_amount)
                SELECT gen_random_uuid(), household_id, id, date_trunc('month', now())::date,
                       0.0100
                FROM goals
                """);

        Instant started = Instant.now();
        ApiResponse list = household.owner().get("/api/goals?size=200");
        Duration took = Duration.between(started, Instant.now());

        assertThat(list.status()).isEqualTo(200);
        assertThat(countOf(list, "\"projectedReason\":\"beyond-horizon\"")).isEqualTo(50);
        assertThat(took).isLessThan(Duration.ofSeconds(10));
    }

    // ------------------------------------------------------------------------------ helpers

    private UUID householdId() {
        return jdbc.queryForObject("SELECT id FROM households", UUID.class);
    }

    private UUID createGoal(ApiClient client, String name) {
        ApiResponse response =
                client.post("/api/goals", "{\"name\":\"" + name + "\",\"targetAmount\":\"6000\"}");
        if (response.status() != 201) {
            throw new AssertionError("could not create a goal: " + response.body());
        }
        return UUID.fromString(response.stringField("id"));
    }

    private void seedGoals(UUID householdId, int count, boolean archived) {
        jdbc.update(
                """
                INSERT INTO goals (id, household_id, name, currency, target_amount, archived)
                SELECT gen_random_uuid(), ?, 'Goal ' || n, 'GBP', 100.0000, ?
                FROM generate_series(1, ?) AS n
                """,
                householdId,
                archived,
                count);
    }

    private void seedContributions(UUID goal, int count) {
        jdbc.update(
                """
                INSERT INTO goal_contributions (id, household_id, goal_id, contributed_on, period,
                                                amount)
                SELECT gen_random_uuid(), g.household_id, g.id, CURRENT_DATE - n,
                       date_trunc('month', CURRENT_DATE - n)::date, 1.0000
                FROM goals g, generate_series(1, ?) AS n
                WHERE g.id = ?
                """,
                count,
                goal);
    }

    private static int countOf(ApiResponse response, String needle) {
        int count = 0;
        for (int at = response.body().indexOf(needle);
                at >= 0;
                at = response.body().indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }
}
