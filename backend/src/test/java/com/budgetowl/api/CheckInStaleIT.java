package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Decision 9: when the data changes underneath a draft, completion refuses the <b>whole</b>
 * check-in, names every affected item with its reason, and writes nothing. One case per row of the
 * feature doc's table, each asserting both halves.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CheckInStaleIT extends CheckInTestBase {

    @Test
    void an_answered_transaction_edited_in_the_ledger_is_subject_changed() {
        assertStale(
                canvas ->
                        ok(
                                canvas.household()
                                        .member()
                                        .patch(
                                                "/api/transactions/" + canvas.id("amazon"),
                                                "{\"note\":\"a gift\",\"version\":0}"),
                                200),
                "alloc:ROLL_OVER",
                List.of("cat:amazon=subject-changed"),
                true);
    }

    @Test
    void an_answered_transaction_categorised_in_the_ledger_is_subject_changed() {
        assertStale(
                canvas ->
                        ok(
                                canvas.household()
                                        .member()
                                        .patch(
                                                "/api/transactions/" + canvas.id("amazon"),
                                                "{\"categoryId\":\"%s\",\"version\":0}"
                                                        .formatted(seededCategoryId("Shopping"))),
                                200),
                "alloc:ROLL_OVER",
                List.of("cat:amazon=subject-changed"),
                true);
    }

    @Test
    void an_answered_transaction_deleted_takes_its_answer_and_changes_the_basis() {
        assertStale(
                canvas ->
                        ok(
                                canvas.household()
                                        .member()
                                        .delete("/api/transactions/" + canvas.id("amazon")),
                                204),
                "alloc:ROLL_OVER",
                List.of("alloc=surplus-changed"),
                true);
    }

    @Test
    void the_chosen_category_archived_is_target_archived() {
        assertStale(
                canvas ->
                        ok(
                                canvas.household()
                                        .member()
                                        .post(
                                                "/api/categories/"
                                                        + seededCategoryId("Eating Out")
                                                        + "/archive",
                                                null),
                                200),
                "alloc:ROLL_OVER",
                List.of("cat:blueBottle=target-archived"),
                false);
    }

    @Test
    void an_occurrence_recorded_on_bills_and_income_meanwhile_is_already_recorded() {
        assertStale(
                canvas ->
                        ok(
                                canvas.household()
                                        .member()
                                        .put(
                                                "/api/bills/"
                                                        + canvas.id("internet")
                                                        + "/occurrences/2026-08-25",
                                                "{\"status\":\"PAID\"}"),
                                200),
                "alloc:ROLL_OVER",
                List.of("chg:comcast=already-recorded"),
                true);
    }

    @Test
    void the_bill_retired_is_bill_inactive() {
        assertStale(
                canvas -> {
                    long version =
                            canvas.household()
                                    .owner()
                                    .get("/api/bills/" + canvas.id("internet") + "?period=2026-08")
                                    .numberField("version");
                    ok(
                            canvas.household()
                                    .member()
                                    .patch(
                                            "/api/bills/" + canvas.id("internet"),
                                            "{\"active\":false,\"version\":%d}".formatted(version)),
                            200);
                },
                "alloc:ROLL_OVER",
                // Retiring the bill takes its category out of the month's plan, but Comcast's row
                // is
                // answered "attach", so it is out of the week's spending either way: only the
                // attach is refused, and the surplus stands.
                List.of("chg:comcast=bill-inactive"),
                true);
    }

    @Test
    void the_charge_attached_elsewhere_is_transaction_attached_elsewhere() {
        assertStale(
                canvas ->
                        ok(
                                canvas.household()
                                        .member()
                                        .post(
                                                "/api/envelopes/"
                                                        + canvas.id("groceries")
                                                        + "/entries",
                                                "{\"transactionId\":\"%s\"}"
                                                        .formatted(canvas.id("comcast"))),
                                201),
                "alloc:ROLL_OVER",
                List.of("chg:comcast=transaction-attached-elsewhere"),
                true);
    }

    @Test
    void the_goal_archived_is_goal_archived() {
        assertStale(
                canvas ->
                        ok(
                                canvas.household()
                                        .member()
                                        .post("/api/goals/" + canvas.id("fund") + "/archive", null),
                                200),
                "alloc:GOAL",
                List.of("alloc=goal-archived"),
                true);
    }

    @Test
    void the_debt_paid_off_is_debt_paid_off() {
        assertStale(
                canvas ->
                        jdbc.update(
                                "UPDATE debts SET paid_off_at = now() WHERE id = ?",
                                canvas.id("visaDebt")),
                "alloc:DEBT",
                List.of("alloc=debt-paid-off"),
                true);
    }

    @Test
    void new_rows_imported_into_the_window_change_the_basis_and_the_surplus() {
        assertStale(
                canvas ->
                        spend(
                                canvas.household().member(),
                                canvas.id("chase"),
                                "2026-08-21",
                                "-12.00",
                                "Corner Shop",
                                null),
                "alloc:ROLL_OVER",
                List.of("alloc=surplus-changed"),
                true);
    }

    @Test
    void a_rule_removed_changes_what_the_week_asks() {
        assertStale(
                canvas -> jdbc.update("DELETE FROM charge_rules"),
                "alloc:ROLL_OVER",
                List.of("odd:gymCharge=subject-changed"),
                true);
    }

    // ---------------------------------------------------------------------------- the check

    /**
     * Answers the canvas, lets {@code meanwhile} change something, completes, and asserts the
     * refusal names exactly {@code expected} (as {@code kind:name=reason}), carries {@code
     * basis-changed} when {@code basisMoves}, and that nothing was written.
     */
    private void assertStale(
            Consumer<Canvas> meanwhile,
            String allocation,
            List<String> expected,
            boolean basisMoves) {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        answerTheCanvas(canvas, owner);
        String[] alloc = allocation.split(":");
        String target =
                switch (alloc[1]) {
                    case "GOAL" -> ",\"goalId\":\"" + canvas.id("fund") + "\"";
                    case "DEBT" -> ",\"debtId\":\"" + canvas.id("visaDebt") + "\"";
                    default -> "";
                };
        answer(
                owner,
                "alloc",
                "{\"choice\":\"%s\"%s,\"amount\":\"63.00\"}".formatted(alloc[1], target));
        JsonNode seen = json(owner.get(W34));
        String body =
                "{\"version\":%d,\"basis\":\"%s\"}"
                        .formatted(seen.get("version").asLong(), seen.get("basis").asString());

        meanwhile.accept(canvas);
        ApiResponse refused = owner.post(W34 + "/complete", body);

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(refused.stringField("code")).isEqualTo("check-in-stale");
        List<String> named = new ArrayList<>();
        boolean basisChanged = false;
        for (JsonNode item : json(refused).get("params").get("items")) {
            String reason = item.get("reason").asString();
            if (item.get("itemKey") == null) {
                basisChanged = basisChanged || "basis-changed".equals(reason);
                continue;
            }
            named.add(item.get("itemKey").asString() + "=" + reason);
        }
        List<String> wanted = new ArrayList<>();
        for (String entry : expected) {
            // "cat:amazon=subject-changed" names the item by kind and canvas id; "alloc=…" is a
            // key.
            String[] keyAndReason = entry.split("=");
            String[] kindAndName = keyAndReason[0].split(":");
            String key =
                    kindAndName.length == 1
                            ? kindAndName[0]
                            : kindAndName[0] + "." + canvas.id(kindAndName[1]);
            wanted.add(key + "=" + keyAndReason[1]);
        }
        assertThat(named).containsExactlyInAnyOrderElementsOf(wanted);
        assertThat(basisChanged).as("basis-changed").isEqualTo(basisMoves);

        // Nothing was written.
        assertThat(categoryOf(canvas.id("blueBottle"))).isNull();
        assertThat(categoryOf(canvas.id("wholeFoods"))).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payee_rules", Long.class)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM bill_occurrences WHERE bill_id = ?",
                                Long.class,
                                canvas.id("gym")))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM goal_contributions WHERE provenance = 'CHECK_IN'",
                                Long.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM debt_payments WHERE provenance = 'CHECK_IN'",
                                Long.class))
                .isZero();
        assertThat(owner.get(W34).stringField("status")).isEqualTo("DRAFT");
    }

    private UUID categoryOf(UUID transaction) {
        return jdbc.queryForObject(
                "SELECT category_id FROM transactions WHERE id = ?", UUID.class, transaction);
    }
}
