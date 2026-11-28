package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * "What a check-in never does", asserted against the database after a completion that exercises
 * every kind of answer: an overdue bill marked paid with the debt-plan prompt accepted, a prepaid
 * charge, three categories with a remembered rule, an attach with a remembered mapping, and an
 * allocation to a debt.
 *
 * <p><b>It never moves money and never creates, deletes, pairs or splits a transaction.</b> The
 * only change it may make to a transaction is its category, on a row someone answered. It never
 * files into an envelope and never changes a plan.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CheckInNeverIT extends CheckInTestBase {

    /** Every column of every transaction a check-in must leave alone. */
    private static final String LEDGER =
            """
            SELECT id::text || '|' || account_id || '|' || date || '|' || amount || '|'
                   || coalesce(payee_id::text, '-') || '|' || coalesce(note, '-') || '|' || status
                   || '|' || coalesce(transfer_group_id::text, '-') || '|'
                   || coalesce(external_id, '-')
              FROM transactions ORDER BY id
            """;

    /** Every plan figure: the debt plan, goal steps, envelope amounts, and bills. */
    private static final String PLANS =
            """
            SELECT 'plan|' || strategy || '|' || monthly_amount FROM payoff_plans
            UNION ALL SELECT 'step|' || goal_id || '|' || from_period || '|' || monthly_amount
                        FROM goal_contribution_steps
            UNION ALL SELECT 'envelope|' || id || '|' || amount FROM envelopes
            UNION ALL SELECT 'bill|' || id || '|' || expected_amount || '|' || schedule || '|'
                             || coalesce(day_of_month::text, '-') || '|' || active FROM bills
            UNION ALL SELECT 'goal|' || id || '|' || target_amount || '|' || starting_amount FROM goals
            UNION ALL SELECT 'debt|' || id || '|' || minimum_payment || '|' || anchor_balance FROM debts
            ORDER BY 1
            """;

    @Test
    void a_completion_of_every_answer_kind_changes_only_categories_it_was_told_to() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        // An overdue occurrence for step 1, on a debt's bill row, so the debt-plan prompt is part
        // of the answer (debt-plan.md decision 3).
        UUID storeCard = bill(owner, "Store card", "25.00", 19, null);
        UUID storeDebt =
                debt(owner, "Store card", "CREDIT_CARD", "400.00", "9.90", "25.00", storeCard);
        answerTheCanvas(canvas, owner);
        answer(
                owner,
                "bill." + storeCard + ".2026-08-19",
                "{\"choice\":\"MARK_PAID\",\"recordDebtPayment\":true}");
        answer(
                owner,
                "alloc",
                "{\"choice\":\"DEBT\",\"debtId\":\"%s\",\"amount\":\"63.00\"}"
                        .formatted(canvas.id("visaDebt")));
        List<String> ledgerBefore = jdbc.queryForList(LEDGER, String.class);
        List<String> plansBefore = jdbc.queryForList(PLANS, String.class);
        Map<String, Object> categoriesBefore = categories();
        long envelopeEntries = count("SELECT count(*) FROM envelope_entries");

        ok(complete(owner), 200);

        assertThat(jdbc.queryForList(LEDGER, String.class))
                .as("no transaction created, deleted, re-dated, re-amounted, re-paid or paired")
                .isEqualTo(ledgerBefore);
        assertThat(jdbc.queryForList(PLANS, String.class))
                .as("no plan changed: the surplus is a one-off record")
                .isEqualTo(plansBefore);
        assertThat(count("SELECT count(*) FROM envelope_entries")).isEqualTo(envelopeEntries);

        Map<String, Object> categoriesAfter = categories();
        for (Map.Entry<String, Object> row : categoriesBefore.entrySet()) {
            boolean answered =
                    List.of(canvas.id("blueBottle"), canvas.id("wholeFoods"), canvas.id("ride"))
                            .stream()
                            .map(UUID::toString)
                            .anyMatch(row.getKey()::equals);
            if (!answered) {
                assertThat(categoriesAfter.get(row.getKey()))
                        .as("an unanswered row keeps its category")
                        .isEqualTo(row.getValue());
            }
        }
        assertThat(count("SELECT count(*) FROM debt_payments WHERE provenance = 'CHECK_IN'"))
                .as("a plan record of the surplus, and nothing moved at any bank")
                .isEqualTo(1L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status || ' ' || status_source FROM bill_occurrences"
                                        + " WHERE bill_id = ? AND due_date = '2026-08-19'",
                                String.class,
                                storeCard))
                .isEqualTo("PAID MANUAL");
        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT p.provenance || ' ' || p.amount FROM debt_payments p
                                  JOIN bill_occurrences o ON o.id = p.bill_occurrence_id
                                 WHERE p.debt_id = ?
                                """,
                                String.class,
                                storeDebt))
                .as("the accepted prompt records the occurrence's amount against the debt")
                .isEqualTo("USER_LOGGED 25.0000");
    }

    @Test
    void a_transfer_is_never_an_item_and_never_spending() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        UUID savings = createAccount(owner, "Savings", null);
        var before = json(owner.get(W34));
        ok(
                owner.post(
                        "/api/transfers",
                        """
                        {"fromAccountId":"%s","toAccountId":"%s","date":"2026-08-20","amount":"500.00"}
                        """
                                .formatted(canvas.id("chase"), savings)),
                201);

        var after = json(owner.get(W34));

        assertThat(amount(figures(after).get("weekSpending")))
                .isEqualTo(amount(figures(before).get("weekSpending")));
        assertThat(after.toString()).doesNotContain("\"500.0000\"").doesNotContain("\"-500.0000\"");
        assertThat(step(after, "CATEGORIZE").get("questions").asInt())
                .isEqualTo(step(before, "CATEGORIZE").get("questions").asInt());
    }

    private Map<String, Object> categories() {
        Map<String, Object> rows = new java.util.HashMap<>();
        for (Map<String, Object> row :
                jdbc.queryForList("SELECT id::text AS id, category_id FROM transactions")) {
            rows.put((String) row.get("id"), row.get("category_id"));
        }
        return rows;
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
