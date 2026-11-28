package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.checkin.service.ChargeRuleService;
import com.budgetowl.checkin.service.CheckInPictures;
import com.budgetowl.checkin.service.CheckInService;
import com.budgetowl.checkin.service.PayeeRuleService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Every collection a member's writes can grow is capped before insert, and every read is bounded
 * (threat model, attack 11; the slice-11 and slice-12 lessons). Each test here fails if its cap is
 * removed: the sabotage proofs are recorded in the feature doc.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CheckInLimitsIT extends CheckInTestBase {

    @Test
    void a_step_lists_at_most_two_hundred_items_and_says_how_many_more() {
        Canvas canvas = canvasWeek();
        insertUncategorised(canvas, 250);

        JsonNode week = json(ok(canvas.household().owner().get(W34), 200));

        JsonNode step = step(week, "CATEGORIZE");
        assertThat(step.get("items")).hasSize(CheckInPictures.ITEMS_PER_STEP);
        assertThat(step.get("more").asLong())
                .as("253 uncategorised rows, 200 shown")
                .isEqualTo(53L);
    }

    @Test
    void a_new_payee_rule_past_the_cap_refuses_the_whole_completion() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        answerTheCanvas(canvas, owner);
        answer(owner, "alloc", "{\"choice\":\"ROLL_OVER\",\"amount\":\"63.00\"}");
        UUID household = jdbc.queryForObject("SELECT id FROM households", UUID.class);
        jdbc.update(
                """
                WITH made AS (
                    INSERT INTO payees (id, household_id, name)
                    SELECT gen_random_uuid(), ?, 'Shop ' || n FROM generate_series(1, ?) n
                    RETURNING id)
                INSERT INTO payee_rules (id, household_id, payee_id, category_id)
                SELECT gen_random_uuid(), ?, id, ? FROM made
                """,
                household,
                PayeeRuleService.MAXIMUM_PER_HOUSEHOLD,
                household,
                seededCategoryId("Shopping"));

        ApiResponse refused = complete(owner);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.stringField("code")).isEqualTo("check-in-stale");
        assertThat(refused.body())
                .contains("cat." + canvas.id("blueBottle"))
                .contains("limit-reached");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payee_rules", Long.class))
                .isEqualTo((long) PayeeRuleService.MAXIMUM_PER_HOUSEHOLD);
        assertThat(categoryOf(canvas.id("ride"))).as("and nothing else was written").isNull();
    }

    @Test
    void replacing_a_payee_rule_at_the_cap_is_not_a_new_one() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        answerTheCanvas(canvas, owner);
        answer(owner, "alloc", "{\"choice\":\"ROLL_OVER\",\"amount\":\"63.00\"}");
        UUID household = jdbc.queryForObject("SELECT id FROM households", UUID.class);
        UUID blueBottle =
                jdbc.queryForObject(
                        "SELECT id FROM payees WHERE name = 'SQ *BLUE BOTTLE'", UUID.class);
        jdbc.update(
                "INSERT INTO payee_rules (id, household_id, payee_id, category_id)"
                        + " VALUES (gen_random_uuid(), ?, ?, ?)",
                household,
                blueBottle,
                seededCategoryId("Shopping"));
        jdbc.update(
                """
                WITH made AS (
                    INSERT INTO payees (id, household_id, name)
                    SELECT gen_random_uuid(), ?, 'Shop ' || n FROM generate_series(1, ?) n
                    RETURNING id)
                INSERT INTO payee_rules (id, household_id, payee_id, category_id)
                SELECT gen_random_uuid(), ?, id, ? FROM made
                """,
                household,
                PayeeRuleService.MAXIMUM_PER_HOUSEHOLD - 1,
                household,
                seededCategoryId("Shopping"));

        assertThat(complete(owner).status()).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT category_id FROM payee_rules WHERE payee_id = ?",
                                UUID.class,
                                blueBottle))
                .isEqualTo(seededCategoryId("Eating Out"));
    }

    @Test
    void a_new_charge_rule_past_the_cap_refuses_the_whole_completion() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        answerTheCanvas(canvas, owner);
        answer(owner, "alloc", "{\"choice\":\"ROLL_OVER\",\"amount\":\"63.00\"}");
        UUID household = jdbc.queryForObject("SELECT id FROM households", UUID.class);
        jdbc.update(
                """
                WITH made AS (
                    INSERT INTO payees (id, household_id, name)
                    SELECT gen_random_uuid(), ?, 'Biller ' || n FROM generate_series(1, ?) n
                    RETURNING id)
                INSERT INTO charge_rules (id, household_id, bill_id, payee_id, account_id)
                SELECT gen_random_uuid(), ?, ?, id, ? FROM made
                """,
                household,
                ChargeRuleService.MAXIMUM_PER_HOUSEHOLD - 1,
                household,
                canvas.id("rent"),
                canvas.id("chase"));
        // The basis counts charge rules, so the person re-reads before finishing, as they would.
        ApiResponse refused = complete(owner);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body())
                .contains("chg." + canvas.id("comcast"))
                .contains("limit-reached");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM charge_rules", Long.class))
                .isEqualTo((long) ChargeRuleService.MAXIMUM_PER_HOUSEHOLD);
    }

    @Test
    void a_draft_takes_at_most_a_thousand_answers() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        ok(owner.put(W34, null), 201);
        UUID household = jdbc.queryForObject("SELECT id FROM households", UUID.class);
        UUID draft =
                jdbc.queryForObject("SELECT id FROM check_ins WHERE status = 'DRAFT'", UUID.class);
        // Answers whose rows have since stopped deriving (re-filed in the ledger): the backstop
        // against a loop that grows a draft without bound.
        jdbc.update(
                """
                WITH made AS (
                    INSERT INTO transactions (id, household_id, account_id, date, amount, status,
                                              category_id)
                    SELECT gen_random_uuid(), ?, ?, DATE '2026-08-20', -1.00, 'CLEARED', ?
                      FROM generate_series(1, ?)
                    RETURNING id)
                INSERT INTO check_in_answers (id, household_id, check_in_id, item_kind, choice,
                                              transaction_id)
                SELECT gen_random_uuid(), ?, ?, 'CATEGORIZE', 'LEAVE', id FROM made
                """,
                household,
                canvas.id("chase"),
                seededCategoryId("Shopping"),
                CheckInService.MAXIMUM_ANSWERS_PER_CHECK_IN,
                household,
                draft);

        ApiResponse refused =
                owner.put(W34 + "/answers/cat." + canvas.id("ride"), "{\"choice\":\"LEAVE\"}");

        assertThat(refused.status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM check_in_answers", Long.class))
                .isEqualTo((long) CheckInService.MAXIMUM_ANSWERS_PER_CHECK_IN);
    }

    private void insertUncategorised(Canvas canvas, int rows) {
        UUID household = jdbc.queryForObject("SELECT id FROM households", UUID.class);
        jdbc.update(
                """
                INSERT INTO transactions (id, household_id, account_id, date, amount, status)
                SELECT gen_random_uuid(), ?, ?, DATE '2026-08-17' + (n % 7), -1.00, 'CLEARED'
                  FROM generate_series(1, ?) n
                """,
                household, canvas.id("chase"), rows);
    }

    private UUID categoryOf(UUID transaction) {
        return jdbc.queryForObject(
                "SELECT category_id FROM transactions WHERE id = ?", UUID.class, transaction);
    }
}
