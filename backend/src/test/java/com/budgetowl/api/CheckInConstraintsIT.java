package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.JsonNode;

/**
 * V20's constraints, each proved by writing straight to the table past every service check: the
 * database is the backstop, so it must refuse on its own (docs/guides/database-style.md).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CheckInConstraintsIT extends CheckInTestBase {

    private Canvas canvas;
    private UUID ours;
    private UUID other;
    private UUID otherCheckIn;
    private UUID otherTransaction;
    private UUID otherPayee;

    @BeforeEach
    void twoHouseholds() {
        canvas = canvasWeek();
        ours = jdbc.queryForObject("SELECT id FROM households", UUID.class);
        other = UUID.randomUUID();
        // uq_households_singleton (V3) permits one household (ADR-0026). A scoping rule can only be
        // shown to hold against a second one, so the index is lifted here and restored after each
        // test, as DebtSchemaConstraintsIT does.
        jdbc.execute("DROP INDEX IF EXISTS uq_households_singleton");
        // One statement, so the household has its OWNER by the time the deferred last-owner
        // trigger checks at commit.
        jdbc.update(
                """
                WITH h AS (INSERT INTO households (id, name, base_currency)
                           VALUES (?, 'Elsewhere', 'GBP') RETURNING id),
                     u AS (INSERT INTO users (id, email, display_name)
                           VALUES (gen_random_uuid(), 'mallory@example.com', 'Someone') RETURNING id)
                INSERT INTO household_members (id, household_id, user_id, role)
                SELECT gen_random_uuid(), h.id, u.id, 'OWNER' FROM h, u
                """,
                other);
        // The other household's own rows, so each foreign-key test below violates exactly the
        // reference it names and nothing else: a CHECK is evaluated before any foreign key.
        UUID otherAccount = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO accounts (id, household_id, name, type, currency, opening_balance)"
                        + " VALUES (?, ?, 'Theirs', 'CHECKING', 'GBP', 0)",
                otherAccount,
                other);
        otherTransaction = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO transactions (id, household_id, account_id, date, amount, status)"
                        + " VALUES (?, ?, ?, DATE '2026-08-19', -1.00, 'CLEARED')",
                otherTransaction,
                other,
                otherAccount);
        otherPayee = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO payees (id, household_id, name) VALUES (?, ?, 'Their shop')",
                otherPayee,
                other);
        otherCheckIn = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO check_ins (id, household_id, week_start, status, window_start)
                VALUES (?, ?, DATE '2026-08-17', 'DRAFT', DATE '2026-08-17')
                """,
                otherCheckIn,
                other);
    }

    @AfterEach
    void restoreTheSingleton() {
        jdbc.update("DELETE FROM households WHERE id = ?", other);
        jdbc.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS uq_households_singleton ON households ((true))");
    }

    // ----------------------------------------------------------------------- through the API

    /**
     * The other household has an uncategorised outflow in this very window, in the same currency,
     * and a draft for this very week. None of it may reach our check-in, and its row's key is a
     * {@code 404} like any key that is not ours.
     */
    @Test
    void another_households_rows_and_draft_never_reach_ours() {
        ApiClient owner = canvas.household().owner();
        String before = owner.get(W34).body();

        ApiResponse started = owner.put(W34, null);
        JsonNode week = json(started);

        assertThat(started.status()).as("their draft does not block ours").isEqualTo(201);
        assertThat(before).doesNotContain(otherTransaction.toString());
        assertThat(week.toString()).doesNotContain(otherTransaction.toString());
        assertThat(
                        owner.put(
                                        W34 + "/answers/cat." + otherTransaction,
                                        "{\"choice\":\"LEAVE\"}")
                                .status())
                .isEqualTo(404);
        String withTheirs = amount(figures(week).get("weekSpending"));
        jdbc.update("DELETE FROM transactions WHERE id = ?", otherTransaction);
        assertThat(amount(figures(json(owner.get(W34))).get("weekSpending")))
                .as("their $1.00 was never our spending")
                .isEqualTo(withTheirs);
    }

    // ------------------------------------------------------------------------------- check_ins

    @Test
    void a_second_draft_for_a_household_is_refused() {
        assertRefusedBy(
                "ux_check_ins_one_draft",
                () -> checkIn(other, "2026-08-10", "2026-08-10", "DRAFT"));
    }

    @Test
    void a_second_check_in_for_a_week_is_refused() {
        jdbc.update(
                "UPDATE check_ins SET status = 'COMPLETED', completed_at = now(),"
                        + " currency = 'GBP', week_spending = 0, next_week_target = 0 WHERE id = ?",
                otherCheckIn);
        assertRefusedBy(
                "uq_check_ins_household_week",
                () -> checkIn(other, "2026-08-17", "2026-08-17", "DRAFT"));
    }

    @Test
    void a_week_that_does_not_start_on_monday_is_refused() {
        assertRefusedBy(
                "ck_check_ins_week_start_is_monday",
                () -> checkIn(ours, "2026-08-18", "2026-08-18", "DRAFT"));
    }

    @Test
    void a_window_more_than_twenty_one_days_back_is_refused() {
        jdbc.update("DELETE FROM check_ins WHERE household_id = ?", ours);
        assertRefusedBy(
                "ck_check_ins_window", () -> checkIn(ours, "2026-08-17", "2026-07-26", "DRAFT"));
        assertRefusedBy(
                "ck_check_ins_window", () -> checkIn(ours, "2026-08-17", "2026-08-18", "DRAFT"));
    }

    @Test
    void a_draft_with_a_snapshot_or_a_completion_without_one_is_refused() {
        assertRefusedBy(
                "ck_check_ins_snapshot",
                () ->
                        jdbc.update(
                                "UPDATE check_ins SET week_spending = 487 WHERE id = ?",
                                otherCheckIn));
        assertRefusedBy(
                "ck_check_ins_snapshot",
                () ->
                        jdbc.update(
                                "UPDATE check_ins SET status = 'COMPLETED', completed_at = now()"
                                        + " WHERE id = ?",
                                otherCheckIn));
    }

    // -------------------------------------------------------------------- answer shapes

    @Test
    void an_overdue_answer_without_its_occurrence_is_refused() {
        assertRefusedBy(
                "ck_check_in_answers_overdue_bill",
                () -> answer("OVERDUE_BILL", "MARK_PAID", null, null, null, null));
    }

    @Test
    void a_prepaid_answer_without_its_target_is_refused() {
        assertRefusedBy(
                "ck_check_in_answers_unusual_charge",
                () ->
                        answer(
                                "UNUSUAL_CHARGE",
                                "PREPAID",
                                canvas.id("gymCharge"),
                                null,
                                null,
                                null));
    }

    @Test
    void a_category_answer_without_a_category_is_refused() {
        assertRefusedBy(
                "ck_check_in_answers_categorize",
                () -> answer("CATEGORIZE", "CATEGORY", canvas.id("ride"), null, null, null));
    }

    @Test
    void an_attach_without_a_date_is_refused() {
        assertRefusedBy(
                "ck_check_in_answers_bill_charge",
                () ->
                        answer(
                                "BILL_CHARGE",
                                "ATTACH",
                                canvas.id("comcast"),
                                null,
                                canvas.id("internet"),
                                null));
    }

    @Test
    void an_allocation_of_nothing_is_refused() {
        assertRefusedBy(
                "ck_check_in_answers_allocation",
                () ->
                        jdbc.update(
                                """
                                INSERT INTO check_in_answers (id, household_id, check_in_id, item_kind,
                                                              choice, amount)
                                VALUES (gen_random_uuid(), ?, ?, 'ALLOCATION', 'ROLL_OVER', 0)
                                """,
                                ours,
                                ourCheckIn()));
    }

    @Test
    void a_choice_another_kind_takes_is_refused() {
        assertRefusedBy(
                "ck_check_in_answers_categorize",
                () -> answer("CATEGORIZE", "ATTACH", canvas.id("ride"), null, null, null));
    }

    @Test
    void two_answers_to_one_item_are_refused() {
        answer("CATEGORIZE", "LEAVE", canvas.id("ride"), null, null, null);
        assertRefusedBy(
                "ux_check_in_answers_transaction_item",
                () -> answer("CATEGORIZE", "LEAVE", canvas.id("ride"), null, null, null));
    }

    // --------------------------------------------------------- composite foreign keys

    @Test
    void an_answer_cannot_name_another_households_transaction() {
        assertRefusedBy(
                "fk_check_in_answers_transaction",
                () -> otherAnswer(canvas.id("ride"), "CATEGORIZE", "LEAVE"));
    }

    @Test
    void an_answer_cannot_name_another_households_category() {
        assertRefusedBy(
                "fk_check_in_answers_category",
                () ->
                        jdbc.update(
                                """
                                INSERT INTO check_in_answers (id, household_id, check_in_id, item_kind,
                                                              choice, transaction_id, category_id)
                                VALUES (gen_random_uuid(), ?, ?, 'CATEGORIZE', 'CATEGORY', ?, ?)
                                """,
                                other,
                                otherCheckIn,
                                otherTransaction,
                                seededCategoryId("Shopping")));
    }

    @Test
    void an_answer_cannot_name_another_households_bill_goal_or_debt() {
        assertRefusedBy(
                "fk_check_in_answers_bill",
                () ->
                        jdbc.update(
                                """
                                INSERT INTO check_in_answers (id, household_id, check_in_id, item_kind,
                                                              choice, bill_id, due_date)
                                VALUES (gen_random_uuid(), ?, ?, 'OVERDUE_BILL', 'SKIP', ?, DATE '2026-08-25')
                                """,
                                other,
                                otherCheckIn,
                                canvas.id("internet")));
        assertRefusedBy(
                "fk_check_in_answers_goal",
                () ->
                        jdbc.update(
                                """
                                INSERT INTO check_in_answers (id, household_id, check_in_id, item_kind,
                                                              choice, goal_id, amount)
                                VALUES (gen_random_uuid(), ?, ?, 'ALLOCATION', 'GOAL', ?, 63)
                                """,
                                other,
                                otherCheckIn,
                                canvas.id("fund")));
        assertRefusedBy(
                "fk_check_in_answers_debt",
                () ->
                        jdbc.update(
                                """
                                INSERT INTO check_in_answers (id, household_id, check_in_id, item_kind,
                                                              choice, debt_id, amount)
                                VALUES (gen_random_uuid(), ?, ?, 'ALLOCATION', 'DEBT', ?, 63)
                                """,
                                other,
                                otherCheckIn,
                                canvas.id("visaDebt")));
    }

    @Test
    void an_answer_cannot_belong_to_another_households_check_in() {
        assertRefusedBy(
                "fk_check_in_answers_check_in",
                () ->
                        jdbc.update(
                                """
                                INSERT INTO check_in_answers (id, household_id, check_in_id, item_kind,
                                                              choice, amount)
                                VALUES (gen_random_uuid(), ?, ?, 'ALLOCATION', 'ROLL_OVER', 63)
                                """,
                                ours,
                                otherCheckIn));
    }

    @Test
    void a_rule_cannot_name_another_households_payee_category_bill_or_account() {
        UUID payee =
                jdbc.queryForObject("SELECT id FROM payees WHERE name = 'UBER *TRIP'", UUID.class);
        assertRefusedBy(
                "fk_payee_rules_payee",
                () ->
                        jdbc.update(
                                "INSERT INTO payee_rules (id, household_id, payee_id, category_id)"
                                        + " VALUES (gen_random_uuid(), ?, ?, ?)",
                                other,
                                payee,
                                seededCategoryId("Shopping")));
        assertRefusedBy(
                "fk_payee_rules_category",
                () ->
                        jdbc.update(
                                "INSERT INTO payee_rules (id, household_id, payee_id, category_id)"
                                        + " VALUES (gen_random_uuid(), ?, ?, ?)",
                                other,
                                otherPayee,
                                seededCategoryId("Shopping")));
        assertRefusedBy(
                "fk_charge_rules_bill",
                () ->
                        jdbc.update(
                                "INSERT INTO charge_rules (id, household_id, bill_id, payee_id, account_id)"
                                        + " VALUES (gen_random_uuid(), ?, ?, ?, ?)",
                                other,
                                canvas.id("internet"),
                                payee,
                                canvas.id("chase")));
    }

    @Test
    void deleting_an_answered_transaction_takes_the_answer_with_it() {
        answer("CATEGORIZE", "LEAVE", canvas.id("ride"), null, null, null);

        jdbc.update("DELETE FROM transactions WHERE id = ?", canvas.id("ride"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM check_in_answers", Long.class))
                .isZero();
    }

    @Test
    void deleting_a_user_anonymises_their_answers_and_deletes_nothing() {
        // A user with nothing else attached, so its delete is about the answer and nothing else.
        UUID grace = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, display_name) VALUES (?, 'leaver@example.com', 'Leaver')",
                grace);
        jdbc.update(
                """
                INSERT INTO check_in_answers (id, household_id, check_in_id, item_kind, choice,
                                              transaction_id, answered_by)
                VALUES (gen_random_uuid(), ?, ?, 'CATEGORIZE', 'LEAVE', ?, ?)
                """,
                ours,
                ourCheckIn(),
                canvas.id("ride"),
                grace);

        jdbc.update("DELETE FROM users WHERE id = ?", grace);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM check_in_answers WHERE answered_by IS NULL",
                                Long.class))
                .isEqualTo(1L);
    }

    // ---------------------------------------------------------------------------- helpers

    private UUID ourCheckIn() {
        ensureOurDraft();
        return jdbc.queryForObject(
                "SELECT id FROM check_ins WHERE household_id = ? AND status = 'DRAFT'",
                UUID.class,
                ours);
    }

    private void checkIn(UUID household, String week, String window, String status) {
        jdbc.update(
                "INSERT INTO check_ins (id, household_id, week_start, status, window_start)"
                        + " VALUES (gen_random_uuid(), ?, CAST(? AS date), ?, CAST(? AS date))",
                household,
                week,
                status,
                window);
    }

    private void answer(
            String kind, String choice, UUID transaction, UUID category, UUID bill, UUID goal) {
        UUID checkIn = ourCheckIn();
        jdbc.update(
                """
                INSERT INTO check_in_answers (id, household_id, check_in_id, item_kind, choice,
                                              transaction_id, category_id, bill_id, goal_id)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                ours,
                checkIn,
                kind,
                choice,
                transaction,
                category,
                bill,
                goal);
    }

    private void otherAnswer(UUID transaction, String kind, String choice) {
        jdbc.update(
                "INSERT INTO check_in_answers (id, household_id, check_in_id, item_kind, choice,"
                        + " transaction_id) VALUES (gen_random_uuid(), ?, ?, ?, ?, ?)",
                other,
                otherCheckIn,
                kind,
                choice,
                transaction);
    }

    private void ensureOurDraft() {
        if (jdbc.queryForObject(
                        "SELECT count(*) FROM check_ins WHERE household_id = ? AND status = 'DRAFT'",
                        Long.class,
                        ours)
                == 0) {
            ok(canvas.household().owner().put(W34, null), 201);
        }
    }

    private static void assertRefusedBy(String constraint, Runnable write) {
        Throwable refused = catchThrowable(write::run);
        assertThat(refused)
                .as("%s refuses the row", constraint)
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(refused.getMessage()).contains(constraint);
    }
}
