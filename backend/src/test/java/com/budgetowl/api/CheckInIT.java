package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * The weekly check-in end to end: the canvas's week as numbers, the steps that are skipped, the
 * week range, the allocation rule, and every authorization control over both transports
 * (docs/features/weekly-check-in.md, Testing notes).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CheckInIT extends CheckInTestBase {

    // ------------------------------------------------------------------------ the canvas week

    @Test
    void the_canvas_week_before_any_answer_asks_exactly_its_four_questions() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();

        JsonNode week = json(ok(owner.get(W34), 200));

        assertThat(week.get("status").asString()).isEqualTo("NOT_STARTED");
        assertThat(week.get("windowStart").asString()).isEqualTo("2026-08-17");
        assertThat(item(week, "odd." + canvas.id("gymCharge")).get("multiple").asInt())
                .isEqualTo(3);
        assertThat(item(week, "odd." + canvas.id("gymCharge")).get("skips").asInt()).isEqualTo(2);
        assertThat(step(week, "CATEGORIZE").get("questions").asInt()).isEqualTo(3);
        JsonNode comcast = item(week, "chg." + canvas.id("comcast"));
        assertThat(comcast.get("reason").asString()).isEqualTo("RECURRING");
        assertThat(comcast.get("suggestedBillId").asString())
                .isEqualTo(canvas.id("internet").toString());
        assertThat(comcast.get("suggestedDueDate").asString()).isEqualTo("2026-08-25");
        JsonNode income = step(week, "WHATS_NEW").get("income").get(0);
        assertThat(income.get("name").asString()).isEqualTo("Northwind Systems");
        assertThat(income.get("transactionId").asString())
                .isEqualTo(canvas.id("paycheck").toString());
        assertThat(amount(figures(week).get("weekTarget"))).isEqualTo("550.0000");
        assertThat(amount(figures(week).get("weekSpending")))
                .as("before any answer the three uncategorised rows count as spending")
                .isEqualTo("581.1000");
    }

    @Test
    void the_canvas_week_reconciles_to_the_cent_once_answered() {
        Canvas canvas = canvasWeek();

        JsonNode week = answerTheCanvas(canvas, canvas.household().owner());

        JsonNode figures = figures(week);
        assertThat(amount(figures.get("weekTarget"))).isEqualTo("550.0000");
        assertThat(amount(figures.get("weekSpending"))).isEqualTo("487.0000");
        assertThat(amount(figures.get("surplus"))).isEqualTo("63.0000");
        assertThat(amount(figures.get("base"))).isEqualTo("480.0000");
        assertThat(amount(figures.get("rolledOver"))).isEqualTo("543.0000");
        assertThat(amount(figures.get("basePerDay"))).isEqualTo("68.5700");
        JsonNode goal = choice(week, "GOAL");
        assertThat(amount(goal.get("savedNow"))).isEqualTo("2340.0000");
        assertThat(amount(goal.get("savedWith"))).isEqualTo("2403.0000");
        JsonNode debt = choice(week, "DEBT");
        assertThat(debt.get("debtId").asString())
                .as("the plan's rung-1 debt, the Visa, under avalanche")
                .isEqualTo(canvas.id("visaDebt").toString());
        assertThat(java.time.YearMonth.parse(debt.get("payoffMonthWith").asString()))
                .as("paying more never moves the payoff month later")
                .isBeforeOrEqualTo(
                        java.time.YearMonth.parse(debt.get("payoffMonthNow").asString()));
    }

    @Test
    void a_surplus_paid_to_a_debt_is_principal_only_and_lowers_the_balance_by_exactly_itself() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        answerTheCanvas(canvas, owner);
        answer(
                owner,
                "alloc",
                "{\"choice\":\"DEBT\",\"debtId\":\"%s\",\"amount\":\"63.00\"}"
                        .formatted(canvas.id("visaDebt")));

        ok(complete(owner), 200);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT interest || ' ' || principal FROM debt_payments"
                                        + " WHERE provenance = 'CHECK_IN'",
                                String.class))
                .as("an extra payment carries no second month of interest")
                .isEqualTo("0.0000 63.0000");
    }

    @Test
    void completing_the_canvas_week_writes_every_record_it_showed() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        answerTheCanvas(canvas, owner);
        answer(
                owner,
                "alloc",
                "{\"choice\":\"GOAL\",\"goalId\":\"%s\",\"amount\":\"63.00\"}"
                        .formatted(canvas.id("fund")));

        JsonNode done = json(ok(complete(owner), 200));

        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(amount(done.get("snapshot").get("nextWeekTarget"))).isEqualTo("480.0000");
        assertThat(owner.get("/api/goals/" + canvas.id("fund")).stringField("saved"))
                .isEqualTo("2403.0000");
        assertThat(categoryOf(canvas.id("blueBottle"))).isEqualTo(seededCategoryId("Eating Out"));
        assertThat(categoryOf(canvas.id("wholeFoods"))).isEqualTo(seededCategoryId("Groceries"));
        assertThat(
                        jdbc.queryForList(
                                "SELECT due_date::text || ' ' || status FROM bill_occurrences"
                                        + " WHERE bill_id = ? ORDER BY due_date",
                                String.class,
                                canvas.id("gym")))
                .containsExactly("2026-08-18 PAID", "2026-09-18 SKIPPED", "2026-10-18 SKIPPED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status_source || ' ' || transaction_id FROM bill_occurrences"
                                        + " WHERE bill_id = ? AND due_date = '2026-08-25'",
                                String.class,
                                canvas.id("internet")))
                .isEqualTo("MANUAL " + canvas.id("comcast"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payee_rules", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM charge_rules", Long.class))
                .isEqualTo(2L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT provenance FROM goal_contributions WHERE amount = 63",
                                String.class))
                .isEqualTo("CHECK_IN");
    }

    @Test
    void a_check_in_contribution_counts_on_top_of_the_months_plan() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        String before = owner.get("/api/periods/2026-08/summary").stringField("goalsSetAside");
        answerTheCanvas(canvas, owner);
        answer(
                owner,
                "alloc",
                "{\"choice\":\"GOAL\",\"goalId\":\"%s\",\"amount\":\"63.00\"}"
                        .formatted(canvas.id("fund")));

        ok(complete(owner), 200);

        assertThat(before).isEqualTo("200.0000");
        assertThat(owner.get("/api/periods/2026-08/summary").stringField("goalsSetAside"))
                .as("max(200 planned, 0 recorded otherwise) + 63 from the check-in (decision 6)")
                .isEqualTo("263.0000");
    }

    @Test
    void rolling_the_surplus_over_sets_next_weeks_target_to_base_plus_surplus() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        answerTheCanvas(canvas, owner);
        answer(owner, "alloc", "{\"choice\":\"ROLL_OVER\",\"amount\":\"63.00\"}");

        JsonNode done = json(ok(complete(owner), 200));

        assertThat(amount(done.get("snapshot").get("nextWeekTarget"))).isEqualTo("543.0000");
        assertThat(amount(done.get("snapshot").get("rolledOver"))).isEqualTo("63.0000");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM goal_contributions WHERE provenance = 'CHECK_IN'",
                                Long.class))
                .isZero();
    }

    @Test
    void an_allocation_amount_other_than_the_surplus_is_refused() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        answerTheCanvas(canvas, owner);

        ApiResponse refused =
                owner.put(
                        W34 + "/answers/alloc",
                        "{\"choice\":\"GOAL\",\"goalId\":\"%s\",\"amount\":\"5000.00\"}"
                                .formatted(canvas.id("fund")));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.stringField("code")).isEqualTo("check-in-stale");
        assertThat(refused.body()).contains("surplus-changed");
    }

    @Test
    void a_debt_that_is_not_rung_one_is_not_an_allocation_target() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        answerTheCanvas(canvas, owner);

        ApiResponse refused =
                owner.put(
                        W34 + "/answers/alloc",
                        "{\"choice\":\"DEBT\",\"debtId\":\"%s\",\"amount\":\"63.00\"}"
                                .formatted(canvas.id("carDebt")));

        assertThat(refused.status()).isEqualTo(404);
    }

    @Test
    void a_surplus_offered_and_unanswered_refuses_completion() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        answerTheCanvas(canvas, owner);

        ApiResponse refused = complete(owner);

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.stringField("code")).isEqualTo("check-in-incomplete");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payee_rules", Long.class)).isZero();
    }

    // ----------------------------------------------------------------------- skipped steps

    @Test
    void a_quiet_week_shows_only_next_week_and_finishes_in_one_tap() {
        Household household = seedHousehold();
        ApiClient owner = household.owner();

        JsonNode week = json(ok(owner.put(currentWeek(), null), 201));

        List<String> shown = new ArrayList<>();
        for (JsonNode step : week.get("steps")) {
            shown.add(step.get("step").asString());
        }
        assertThat(shown).containsExactly("NEXT_WEEK");
        assertThat(
                        ok(owner.post(currentWeek() + "/complete", completeBody(week)), 200)
                                .stringField("status"))
                .isEqualTo("COMPLETED");
    }

    @Test
    void a_row_placed_by_a_payee_rule_is_pre_answered_and_skips_step_two() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        answerTheCanvas(canvas, owner);
        answer(owner, "alloc", "{\"choice\":\"ROLL_OVER\",\"amount\":\"63.00\"}");
        ok(complete(owner), 200);

        // Week 35: another Blue Bottle, which the rule written in week 34 places.
        at(Instant.parse("2026-08-28T12:00:00Z"));
        UUID coffee =
                spend(owner, canvas.id("chase"), "2026-08-25", "-6.50", "SQ *BLUE BOTTLE", null);
        JsonNode week = json(ok(owner.get("/api/check-ins/2026-W35"), 200));

        assertThat(step(week, "CATEGORIZE"))
                .as("every row is placed, so the step is skipped")
                .isNull();
        assertThat(week.get("byRule").get("categorized").asInt()).isEqualTo(1);
        assertThat(categoryOf(coffee))
                .as("a rule only pre-answers; nothing is filed until completion")
                .isNull();
    }

    // ------------------------------------------------------------------------- week range

    @Test
    void the_current_week_and_the_one_before_can_be_started_and_no_other() {
        Household household = seedHousehold();
        at(Instant.parse("2026-08-19T12:00:00Z"));

        assertThat(household.owner().get("/api/check-ins/2026-W34").status()).isEqualTo(200);
        assertThat(household.owner().get("/api/check-ins/2026-W33").status()).isEqualTo(200);
        assertThat(household.owner().get("/api/check-ins/2026-W32").status()).isEqualTo(422);
        assertThat(household.owner().get("/api/check-ins/2026-W35").status()).isEqualTo(422);
        assertThat(household.owner().put("/api/check-ins/2019-W02", null).stringField("code"))
                .isEqualTo("check-in-week-out-of-range");
    }

    @Test
    void current_is_the_week_containing_the_servers_today() {
        Household household = seedHousehold();
        at(Instant.parse("2026-08-19T12:00:00Z"));

        ApiResponse current = household.viewer().get("/api/check-ins/current");

        assertThat(current.status()).isEqualTo(200);
        assertThat(current.stringField("week")).isEqualTo("2026-W34");
    }

    @Test
    void a_day_of_slack_either_side_of_monday_moves_the_range_by_a_week() {
        Household household = seedHousehold();

        at(Instant.parse("2026-08-16T23:30:00Z"));
        assertThat(household.owner().get("/api/check-ins/2026-W34").status())
                .as("Sunday night UTC is already Monday further east")
                .isEqualTo(200);
        at(Instant.parse("2026-08-17T00:30:00Z"));
        assertThat(household.owner().get("/api/check-ins/2026-W32").status())
                .as("Monday morning UTC is still Sunday further west")
                .isEqualTo(200);
        at(Instant.parse("2026-08-18T12:00:00Z"));
        assertThat(household.owner().get("/api/check-ins/2026-W32").status()).isEqualTo(422);
    }

    @Test
    void a_malformed_or_impossible_week_is_a_400_naming_it() {
        Household household = seedHousehold();

        // 2025 has 52 ISO weeks (it starts on a Wednesday); 2026 and 2020 have 53.
        for (String week :
                List.of("+10000-W01", "2026-W99", "2025-W53", "2026-W1", "x".repeat(5000))) {
            ApiResponse refused = household.owner().get("/api/check-ins/" + week);
            assertThat(refused.status()).as(week.length() > 20 ? "long" : week).isEqualTo(400);
        }
        assertThat(household.owner().get("/api/check-ins/2026-W99").body()).contains("\"week\"");
        assertThat(household.owner().get("/api/check-ins/2020-W53").status())
                .as("2020 has a week 53; it is merely out of range")
                .isEqualTo(422);
    }

    @Test
    void a_malformed_item_key_is_a_400_and_a_well_formed_foreign_one_a_404() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        ok(owner.put(W34, null), 201);

        assertThat(
                        owner.put(W34 + "/answers/" + "a".repeat(101), "{\"choice\":\"LEAVE\"}")
                                .status())
                .isEqualTo(400);
        assertThat(owner.put(W34 + "/answers/cat.NOT-A-UUID", "{\"choice\":\"LEAVE\"}").status())
                .isEqualTo(400);
        for (UUID foreign :
                List.of(
                        UUID.randomUUID(),
                        canvas.id("fund"),
                        canvas.id("tartine"),
                        canvas.id("internet"))) {
            assertThat(
                            owner.put(W34 + "/answers/cat." + foreign, "{\"choice\":\"LEAVE\"}")
                                    .status())
                    .as("an id that does not derive to an item in this week")
                    .isEqualTo(404);
        }
    }

    @Test
    void an_item_key_for_a_row_outside_the_window_is_404() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        ok(owner.put(W34, null), 201);
        UUID old = spend(owner, canvas.id("chase"), "2026-08-02", "-9.99", "Old Shop", null);

        assertThat(owner.put(W34 + "/answers/cat." + old, "{\"choice\":\"LEAVE\"}").status())
                .isEqualTo(404);
    }

    @Test
    void a_category_from_another_entitys_table_or_an_archived_one_is_404() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        ok(owner.put(W34, null), 201);
        UUID archived = createCategory(owner, "Old habits", "EXPENSE", null);
        ok(owner.post("/api/categories/" + archived + "/archive", null), 200);

        for (UUID wrong :
                List.of(canvas.id("fund"), canvas.id("tartine"), archived, UUID.randomUUID())) {
            assertThat(
                            owner.put(
                                            W34 + "/answers/cat." + canvas.id("ride"),
                                            "{\"choice\":\"CATEGORY\",\"categoryId\":\"%s\"}"
                                                    .formatted(wrong))
                                    .status())
                    .isEqualTo(404);
        }
    }

    // ---------------------------------------------------------------------------- drafts

    @Test
    void starting_twice_is_one_draft_and_another_weeks_draft_is_refused_naming_it() {
        Household household = seedHousehold();
        at(Instant.parse("2026-08-19T12:00:00Z"));

        assertThat(household.owner().put("/api/check-ins/2026-W34", null).status()).isEqualTo(201);
        assertThat(household.member().put("/api/check-ins/2026-W34", null).status()).isEqualTo(200);
        ApiResponse other = household.owner().put("/api/check-ins/2026-W33", null);

        assertThat(other.status()).isEqualTo(409);
        assertThat(other.stringField("code")).isEqualTo("check-in-draft-open");
        assertThat(other.body()).contains("2026-W34");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM check_ins", Long.class)).isEqualTo(1L);
    }

    @Test
    void two_members_answering_the_same_row_are_a_stale_version_for_the_second() {
        Canvas canvas = canvasWeek();
        Household household = canvas.household();
        ok(household.owner().put(W34, null), 201);
        String key = "cat." + canvas.id("ride");
        String body =
                "{\"choice\":\"CATEGORY\",\"categoryId\":\"%s\"}"
                        .formatted(seededCategoryId("Shopping"));

        ok(household.owner().put(W34 + "/answers/" + key, body), 200);
        ApiResponse second = household.member().put(W34 + "/answers/" + key, body);

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.stringField("code")).isEqualTo("stale-version");
        JsonNode answer = item(json(household.member().get(W34)), key).get("answer");
        assertThat(answer.get("answeredBy").get("name").asString()).isEqualTo("Ada");
    }

    @Test
    void every_answer_moves_the_drafts_version_so_a_stale_finisher_is_refused() {
        Canvas canvas = canvasWeek();
        Household household = canvas.household();
        JsonNode started = json(ok(household.owner().put(W34, null), 201));
        answer(household.member(), "cat." + canvas.id("ride"), "{\"choice\":\"LEAVE\"}");

        ApiResponse refused = household.owner().post(W34 + "/complete", completeBody(started));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.stringField("code")).isEqualTo("stale-version");
    }

    @Test
    void discarding_deletes_the_draft_and_its_answers_and_writes_nothing_else() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        answerTheCanvas(canvas, owner);

        assertThat(owner.delete(W34).status()).isEqualTo(204);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM check_in_answers", Long.class))
                .isZero();
        assertThat(categoryOf(canvas.id("ride"))).isNull();
        assertThat(owner.get(W34).stringField("status")).isEqualTo("NOT_STARTED");
    }

    @Test
    void a_completed_check_in_cannot_be_discarded_answered_or_completed_again() {
        Household household = seedHousehold();
        ApiClient owner = household.owner();
        JsonNode week = json(ok(owner.put(currentWeek(), null), 201));
        ok(owner.post(currentWeek() + "/complete", completeBody(week)), 200);

        assertThat(owner.delete(currentWeek()).stringField("code")).isEqualTo("check-in-completed");
        assertThat(owner.put(currentWeek(), null).stringField("code"))
                .isEqualTo("check-in-completed");
        assertThat(owner.post(currentWeek() + "/complete", completeBody(week)).stringField("code"))
                .isEqualTo("check-in-completed");
    }

    // ---------------------------------------------------------------------- authorization

    /** The eleven routes of the API table plus {@code /current}, each with a valid-looking body. */
    static List<Route> everyRoute() {
        String any = "00000000-0000-4000-8000-00000000f00d";
        return List.of(
                new Route("GET", "/api/check-ins", null),
                new Route("GET", "/api/check-ins/current", null),
                new Route("GET", "/api/check-ins/{week}", null),
                new Route("PUT", "/api/check-ins/{week}", null),
                new Route(
                        "PUT",
                        "/api/check-ins/{week}/answers/cat." + any,
                        "{\"choice\":\"LEAVE\"}"),
                new Route("DELETE", "/api/check-ins/{week}/answers/cat." + any, null),
                new Route(
                        "POST",
                        "/api/check-ins/{week}/complete",
                        "{\"version\":0,\"basis\":\"" + "0".repeat(64) + "\"}"),
                new Route("DELETE", "/api/check-ins/{week}", null),
                new Route("GET", "/api/payee-rules", null),
                new Route("DELETE", "/api/payee-rules/" + any, null),
                new Route("GET", "/api/charge-rules", null),
                new Route("DELETE", "/api/charge-rules/" + any, null));
    }

    static List<Route> everyWrite() {
        return everyRoute().stream().filter(route -> !"GET".equals(route.method())).toList();
    }

    /** A route, with {@code {week}} to be substituted by the caller. */
    record Route(String method, String path, String body) {

        ApiResponse callWith(ApiClient client, String week) {
            return client.send(method, path.replace("{week}", week), body);
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    @Test
    void the_sweep_covers_all_twelve_routes() {
        assertThat(everyRoute()).hasSize(12);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRoute")
    void an_unauthenticated_request_is_refused(Route route) {
        seedHousehold();

        ApiResponse refused = route.callWith(browser(), "2026-W34");

        assertThat(refused.status()).isEqualTo(401);
    }

    /** The role is checked before the week or item is looked up, on a week that has a draft. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void a_viewer_may_write_nothing_on_a_draft(Route route) {
        Canvas canvas = canvasWeek();
        Household household = canvas.household();
        answerTheCanvas(canvas, household.owner());
        long answers = jdbc.queryForObject("SELECT count(*) FROM check_in_answers", Long.class);

        ApiResponse refused = route.callWith(household.viewer(), "2026-W34");

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.stringField("code")).isEqualTo("read-only-role");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM check_in_answers", Long.class))
                .isEqualTo(answers);
        assertThat(household.owner().get(W34).stringField("status")).isEqualTo("DRAFT");
    }

    /** …and on a week that has none, so a {@code 403} says nothing about what exists. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void a_viewer_gets_403_for_a_week_with_nothing_in_it_too(Route route) {
        Household household = seedHousehold();

        ApiResponse refused = route.callWith(household.viewer(), "2019-W02");

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.stringField("code")).isEqualTo("read-only-role");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void a_viewer_on_the_bearer_transport_may_write_nothing_either(Route route) {
        Household household = seedHousehold();
        ApiClient phone = mobile(VIEWER_EMAIL, VIEWER_SECRET, "Alan's phone");

        ApiResponse refused = route.callWith(phone, currentWeekName());

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.stringField("code")).isEqualTo("read-only-role");
        assertThat(household.owner().get("/api/check-ins").numberField("totalElements")).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void a_write_without_a_csrf_token_is_refused_on_the_cookie_transport(Route route) {
        seedHousehold();

        ApiResponse refused =
                route.callWith(
                        signIn(OWNER_EMAIL, OWNER_SECRET).withoutCsrfToken(), currentWeekName());

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.stringField("code")).isEqualTo("csrf-token-required");
    }

    @Test
    void a_viewer_reads_every_item_answer_and_figure() {
        Canvas canvas = canvasWeek();
        Household household = canvas.household();
        JsonNode asOwner = answerTheCanvas(canvas, household.owner());

        JsonNode asViewer = json(ok(household.viewer().get(W34), 200));

        assertThat(asViewer.get("steps")).isEqualTo(asOwner.get("steps"));
        assertThat(asViewer.get("basis").asString()).isEqualTo(asOwner.get("basis").asString());
    }

    @Test
    void the_bearer_transport_authorizes_identically() {
        Canvas canvas = canvasWeek();
        // Tokens are issued at real time: one issued with the clock stopped in August would expire
        // before the database's own created_at, which the token table refuses.
        at(Instant.now());
        ApiClient phone = mobile(OWNER_EMAIL, OWNER_SECRET, "Ada's phone");
        ApiClient viewersPhone = mobile(VIEWER_EMAIL, VIEWER_SECRET, "Alan's phone");
        at(WEEK_34_FRIDAY);

        assertThat(phone.put(W34, null).status()).isEqualTo(201);
        assertThat(
                        phone.put(
                                        W34 + "/answers/cat." + canvas.id("ride"),
                                        "{\"choice\":\"LEAVE\"}")
                                .status())
                .isEqualTo(200);
        assertThat(viewersPhone.get(W34).status()).isEqualTo(200);
        assertThat(
                        viewersPhone
                                .put(
                                        W34 + "/answers/cat." + canvas.id("ride"),
                                        "{\"choice\":\"LEAVE\",\"version\":0}")
                                .status())
                .as("neither transport may skip a check the other applies (ADR-0018)")
                .isEqualTo(403);
    }

    @Test
    void a_member_demoted_mid_draft_cannot_finish() {
        Canvas canvas = canvasWeek();
        Household household = canvas.household();
        answerTheCanvas(canvas, household.member());
        answer(household.member(), "alloc", "{\"choice\":\"ROLL_OVER\",\"amount\":\"63.00\"}");
        ok(
                household
                        .owner()
                        .patch(
                                "/api/households/current/members/" + membershipIdOf(MEMBER_EMAIL),
                                "{\"role\":\"VIEWER\"}"),
                200);

        ApiResponse refused = complete(household.member());

        assertThat(refused.status()).isEqualTo(403);
        assertThat(household.owner().get(W34).stringField("status")).isEqualTo("DRAFT");
    }

    @Test
    void an_unknown_rule_id_is_a_404_for_a_member() {
        Household household = seedHousehold();

        assertThat(household.owner().delete("/api/payee-rules/" + UUID.randomUUID()).status())
                .isEqualTo(404);
        assertThat(household.owner().delete("/api/charge-rules/" + UUID.randomUUID()).status())
                .isEqualTo(404);
    }

    @Test
    void the_bills_list_carries_each_bills_charge_rule() {
        Canvas canvas = canvasWeek();

        ApiResponse bills = canvas.household().owner().get("/api/bills?period=2026-08");

        assertThat(bills.body()).contains("\"payeeName\":\"Mountain Strength Co\"");
        assertThat(bills.body()).contains("\"accountLastFour\":\"4412\"");
    }

    // ---------------------------------------------------------------------------- helpers

    private UUID categoryOf(UUID transaction) {
        return jdbc.queryForObject(
                "SELECT category_id FROM transactions WHERE id = ?", UUID.class, transaction);
    }

    private static JsonNode choice(JsonNode week, String kind) {
        for (JsonNode choice : step(week, "NEXT_WEEK").get("choices")) {
            if (kind.equals(choice.get("choice").asString())) {
                return choice;
            }
        }
        throw new AssertionError("no " + kind + " choice in " + week);
    }

    private static String completeBody(JsonNode week) {
        return "{\"version\":%d,\"basis\":\"%s\"}"
                .formatted(week.get("version").asLong(), week.get("basis").asString());
    }

    private static String currentWeekName() {
        return com.budgetowl.checkin.domain.IsoWeek.containing(
                        java.time.LocalDate.now(java.time.ZoneOffset.UTC))
                .toString();
    }

    private static String currentWeek() {
        return "/api/check-ins/" + currentWeekName();
    }
}
