package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Completion is everything or nothing, exactly once (docs/features/weekly-check-in.md, "Completing
 * a check-in"; threat model, attack 5).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CheckInCompletionIT extends CheckInTestBase {

    @Test
    void a_refusal_in_the_last_write_rolls_back_the_first() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        answerTheCanvas(canvas, owner);
        answer(
                owner,
                "alloc",
                "{\"choice\":\"GOAL\",\"goalId\":\"%s\",\"amount\":\"63.00\"}"
                        .formatted(canvas.id("fund")));
        // The fund at its contribution cap, so the LAST write of the completion is refused after
        // the categories, the occurrences and the rules have all been written.
        jdbc.update(
                """
                INSERT INTO goal_contributions (id, household_id, goal_id, contributed_on, period,
                                                amount)
                SELECT gen_random_uuid(), g.household_id, g.id, DATE '2026-07-01', DATE '2026-07-01',
                       0.01
                  FROM goals g, generate_series(1, 1999)
                 WHERE g.id = ?
                """,
                canvas.id("fund"));

        ApiResponse refused = complete(owner);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.stringField("code")).isEqualTo("check-in-stale");
        assertThat(refused.body()).contains("\"itemKey\":\"alloc\"").contains("limit-reached");
        assertNothingWasWritten(canvas);
    }

    @Test
    void two_concurrent_completions_write_once() throws Exception {
        Canvas canvas = canvasWeek();
        Household household = canvas.household();
        answerTheCanvas(canvas, household.owner());
        answer(
                household.owner(),
                "alloc",
                "{\"choice\":\"GOAL\",\"goalId\":\"%s\",\"amount\":\"63.00\"}"
                        .formatted(canvas.id("fund")));
        JsonNode week = json(household.owner().get(W34));
        String body =
                "{\"version\":%d,\"basis\":\"%s\"}"
                        .formatted(week.get("version").asLong(), week.get("basis").asString());

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<ApiResponse>> results = new ArrayList<>();
        for (ApiClient client : List.of(household.owner(), household.member())) {
            results.add(
                    pool.submit(
                            () -> {
                                start.await();
                                return client.post(W34 + "/complete", body);
                            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<ApiResponse> result : results) {
            statuses.add(result.get(60, TimeUnit.SECONDS).status());
        }
        pool.shutdown();

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(count("SELECT count(*) FROM goal_contributions WHERE provenance = 'CHECK_IN'"))
                .isEqualTo(1L);
        assertThat(count("SELECT count(*) FROM payee_rules")).isEqualTo(1L);
        assertThat(
                        count(
                                "SELECT count(*) FROM bill_occurrences WHERE bill_id = '"
                                        + canvas.id("gym")
                                        + "'"))
                .isEqualTo(3L);
    }

    @Test
    void a_retry_after_completion_is_check_in_completed_and_writes_nothing() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        answerTheCanvas(canvas, owner);
        answer(owner, "alloc", "{\"choice\":\"ROLL_OVER\",\"amount\":\"63.00\"}");
        JsonNode week = json(owner.get(W34));
        String body =
                "{\"version\":%d,\"basis\":\"%s\"}"
                        .formatted(week.get("version").asLong(), week.get("basis").asString());
        ok(owner.post(W34 + "/complete", body), 200);
        long occurrences = count("SELECT count(*) FROM bill_occurrences");

        ApiResponse retry = owner.post(W34 + "/complete", body);

        assertThat(retry.status()).isEqualTo(409);
        assertThat(retry.stringField("code")).isEqualTo("check-in-completed");
        assertThat(count("SELECT count(*) FROM bill_occurrences")).isEqualTo(occurrences);
        assertThat(count("SELECT count(*) FROM payee_rules")).isEqualTo(1L);
    }

    @Test
    void unanswered_items_are_left_exactly_as_they_are() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        ok(owner.put(W34, null), 201);
        answer(owner, "odd." + canvas.id("gymCharge"), "{\"choice\":\"LEAVE\"}");

        JsonNode done = json(ok(complete(owner), 200));

        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(categoryOf(canvas.id("ride"))).isNull();
        assertThat(categoryOf(canvas.id("blueBottle"))).isNull();
        assertThat(count("SELECT count(*) FROM bill_occurrences WHERE transaction_id IS NOT NULL"))
                .isZero();
        assertThat(count("SELECT count(*) FROM payee_rules")).isZero();
        assertThat(amount(done.get("snapshot").get("weekSpending")))
                .as("an unanswered uncategorised outflow counts as spending")
                .isEqualTo("581.1000");
    }

    @Test
    void a_charge_rules_pre_answer_is_applied_at_completion_as_auto_matched() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        answerTheCanvas(canvas, owner);
        answer(owner, "alloc", "{\"choice\":\"ROLL_OVER\",\"amount\":\"63.00\"}");
        ok(complete(owner), 200);

        // Week 37: September's Comcast charge, which the rule week 34 remembered matches exactly.
        at(Instant.parse("2026-09-11T12:00:00Z"));
        UUID september =
                spend(
                        owner,
                        canvas.id("chase"),
                        "2026-09-10",
                        "-71.99",
                        "COMCAST XFINITY",
                        "Phone and internet");
        ok(owner.put("/api/check-ins/2026-W37", null), 201);
        JsonNode week = json(owner.get("/api/check-ins/2026-W37"));
        assertThat(step(week, "BILLS")).as("a rule match is not a question").isNull();
        assertThat(week.get("byRule").get("charges").asInt()).isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM bill_occurrences WHERE transaction_id = '"
                                        + september
                                        + "'"))
                .as("nothing is written before completion")
                .isZero();

        ok(
                owner.post(
                        "/api/check-ins/2026-W37/complete",
                        "{\"version\":%d,\"basis\":\"%s\"}"
                                .formatted(
                                        week.get("version").asLong(),
                                        week.get("basis").asString())),
                200);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT due_date || ' ' || status || ' ' || status_source FROM bill_occurrences"
                                        + " WHERE transaction_id = ?",
                                String.class,
                                september))
                .isEqualTo("2026-09-25 PAID AUTO_MATCHED");
    }

    @Test
    void an_import_into_the_window_leaves_the_row_uncategorised_and_unattached() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        answerTheCanvas(canvas, owner);
        answer(owner, "alloc", "{\"choice\":\"ROLL_OVER\",\"amount\":\"63.00\"}");
        ok(complete(owner), 200);

        // Rules act only through a completed check-in (ADR-0041): a new Blue Bottle row is not
        // filed by being recorded, whatever the rule says.
        at(Instant.parse("2026-08-28T12:00:00Z"));
        UUID coffee =
                spend(owner, canvas.id("chase"), "2026-08-26", "-6.50", "SQ *BLUE BOTTLE", null);
        UUID comcast =
                spend(owner, canvas.id("chase"), "2026-08-26", "-71.99", "COMCAST XFINITY", null);

        assertThat(categoryOf(coffee)).isNull();
        assertThat(
                        count(
                                "SELECT count(*) FROM bill_occurrences WHERE transaction_id = '"
                                        + comcast
                                        + "'"))
                .isZero();
    }

    // ---------------------------------------------------------------------------- helpers

    private void assertNothingWasWritten(Canvas canvas) {
        assertThat(categoryOf(canvas.id("blueBottle"))).isNull();
        assertThat(categoryOf(canvas.id("wholeFoods"))).isNull();
        assertThat(categoryOf(canvas.id("ride"))).isNull();
        assertThat(
                        count(
                                "SELECT count(*) FROM bill_occurrences WHERE bill_id = '"
                                        + canvas.id("gym")
                                        + "'"))
                .isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM bill_occurrences WHERE bill_id = '"
                                        + canvas.id("internet")
                                        + "'"))
                .isZero();
        assertThat(count("SELECT count(*) FROM payee_rules")).isZero();
        assertThat(count("SELECT count(*) FROM charge_rules"))
                .as("only the seeded gym rule")
                .isEqualTo(1L);
        assertThat(count("SELECT count(*) FROM goal_contributions WHERE provenance = 'CHECK_IN'"))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM check_ins WHERE week_start = '2026-08-17'",
                                String.class))
                .isEqualTo("DRAFT");
    }

    private UUID categoryOf(UUID transaction) {
        return jdbc.queryForObject(
                "SELECT category_id FROM transactions WHERE id = ?", UUID.class, transaction);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
