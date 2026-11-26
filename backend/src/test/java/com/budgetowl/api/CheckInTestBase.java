package com.budgetowl.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What every check-in integration test needs: a clock it can stop, and <b>the canvas's own week</b>
 * built through the real API (docs/features/weekly-check-in.md, Step 4: "the canvas figures
 * reconcile").
 *
 * <p>The clock is the report tests' {@code MovableClock}, imported from the same configuration
 * class, so the check-in and report suites share one Spring context rather than starting two.
 *
 * <h2>The canvas week, and why each figure is what it is</h2>
 *
 * <p>August 2026: income 2,620 + 2,000 = 4,620. Bills: rent 1,650 (paid), car 342, internet 71.99,
 * Visa 185, gym 42 = 2,290.99. Groceries envelope 600. Both debts' minimums are bill rows, so the
 * fourth term is 0. The emergency fund plans 200. So {@code left(Aug) = 4,620 − 2,290.99 − 600 − 0
 * − 200 = 1,529.01}.
 *
 * <ul>
 *   <li><b>Week 33</b> (Aug 10–16), the first check-in: unbudgeted Aug 1–16 = 120.44 + 230.00 =
 *       350.44, so {@code free = 1,178.57} and next week (Aug 17–23, 7 of August's 15 remaining
 *       days) is {@code 7 × 1,178.57 ÷ 15 = 549.9993 → 550.00}: the canvas's target.
 *   <li>A late $80 parking charge dated Aug 14 arrives after it: Aug 1–16 is now 430.44.
 *   <li><b>Week 34</b> (Aug 17–23), on Friday the 21st: unbudgeted spending with the draft's
 *       answers applied is 42.30 + 250.80 + 18.50 + 56.00 + 38.00 + 81.40 = <b>487.00</b> (Whole
 *       Foods answered Groceries drops out; the gym and Comcast are budgeted by their bills), so
 *       the surplus is <b>63</b>. {@code free = 1,529.01 − 917.44 = 611.57}; {@code held = 63};
 *       next week is 7 of 8 remaining days: {@code 7 × 548.57 ÷ 8 = 479.99875 → 480.00}, rolled
 *       over <b>543.00</b>. The emergency fund's 2,340 becomes <b>2,403</b>.
 * </ul>
 */
@Import(ReportTestBase.ReportClock.class)
abstract class CheckInTestBase extends ApiTestBase {

    /** Sunday of week 33, mid-day: when the first check-in is finished. */
    protected static final Instant WEEK_33_SUNDAY = CanvasWeek.WEEK_33_SUNDAY;

    /** Friday of week 34, mid-day: the canvas's desktop artboard. */
    protected static final Instant WEEK_34_FRIDAY = CanvasWeek.WEEK_34_FRIDAY;

    protected static final String W33 = "/api/check-ins/2026-W33";
    protected static final String W34 = "/api/check-ins/2026-W34";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private ReportTestBase.MovableClock clock;

    @BeforeEach
    void runAtRealTime() {
        clock.moveTo(Instant.now());
    }

    protected void at(Instant instant) {
        clock.moveTo(instant);
    }

    /** The ids a canvas week was built from, by the names the tests use. */
    protected record Canvas(Household household, Map<String, UUID> ids) {

        UUID id(String name) {
            UUID id = ids.get(name);
            if (id == null) {
                throw new AssertionError("no " + name + " in the canvas week");
            }
            return id;
        }
    }

    /**
     * Builds the canvas week: week 33 completed with a target of 550, the late parking charge, and
     * week 34's rows on Friday the 21st with nothing answered yet. The clock is left at that
     * Friday.
     */
    protected Canvas canvasWeek() {
        Household household = seedHousehold();
        Map<String, UUID> ids = new CanvasWeek(household.owner(), jdbc, this::at, "GBP").build();
        return new Canvas(household, ids);
    }

    /** Answers the canvas week as the canvas does, and returns the week after the last answer. */
    protected JsonNode answerTheCanvas(Canvas canvas, ApiClient client) {
        ok(client.put(W34, null), 201);
        JsonNode last = null;
        for (Map.Entry<String, String> answer :
                CanvasWeek.answers(canvas.ids(), this::seededCategoryId).entrySet()) {
            last = answer(client, answer.getKey(), answer.getValue());
        }
        return last;
    }

    protected JsonNode answer(ApiClient client, String itemKey, String body) {
        return json(ok(client.put(W34 + "/answers/" + itemKey, body), 200));
    }

    /** Completes week 34 from the week as last read. */
    protected ApiResponse complete(ApiClient client) {
        JsonNode week = json(client.get(W34));
        return client.post(
                W34 + "/complete",
                "{\"version\":%d,\"basis\":\"%s\"}"
                        .formatted(week.get("version").asLong(), week.get("basis").asString()));
    }

    // ------------------------------------------------------------------------------- reading

    protected static JsonNode json(ApiResponse response) {
        return JSON.readTree(response.body());
    }

    protected static JsonNode step(JsonNode week, String name) {
        for (JsonNode step : week.get("steps")) {
            if (name.equals(step.get("step").asString())) {
                return step;
            }
        }
        return null;
    }

    protected static JsonNode item(JsonNode week, String itemKey) {
        for (JsonNode step : week.get("steps")) {
            for (JsonNode item : step.get("items")) {
                if (itemKey.equals(item.get("itemKey").asString())) {
                    return item;
                }
            }
        }
        return null;
    }

    protected static JsonNode figures(JsonNode week) {
        return step(week, "NEXT_WEEK").get("figures");
    }

    /** A money object's amount, or null for a JSON null. */
    protected static String amount(JsonNode money) {
        return money == null || money.isNull() ? null : money.get("amount").asString();
    }

    protected static ApiResponse ok(ApiResponse response, int status) {
        if (response.status() != status) {
            throw new AssertionError(
                    "expected " + status + ", got " + response.status() + ": " + response.body());
        }
        return response;
    }

    // ------------------------------------------------------------------------------- fixtures

    protected UUID bill(ApiClient client, String name, String amount, int day, UUID categoryId) {
        return UUID.fromString(
                ok(
                                client.post(
                                        "/api/bills",
                                        """
                                        {"name":"%s","expectedAmount":"%s",
                                         "schedule":{"kind":"MONTHLY","dayOfMonth":%d},"categoryId":%s}
                                        """
                                                .formatted(
                                                        name,
                                                        amount,
                                                        day,
                                                        categoryId == null
                                                                ? "null"
                                                                : "\"" + categoryId + "\"")),
                                201)
                        .stringField("id"));
    }

    protected UUID income(ApiClient client, String name, String amount, int day, UUID account) {
        return UUID.fromString(
                ok(
                                client.post(
                                        "/api/income",
                                        """
                                        {"name":"%s","amount":"%s",
                                         "schedule":{"kind":"MONTHLY","dayOfMonth":%d},"accountId":"%s"}
                                        """
                                                .formatted(name, amount, day, account)),
                                201)
                        .stringField("id"));
    }

    protected UUID debt(
            ApiClient client,
            String name,
            String kind,
            String balance,
            String apr,
            String minimum,
            UUID billId) {
        return UUID.fromString(
                ok(
                                client.post(
                                        "/api/debts",
                                        """
                                        {"name":"%s","kind":"%s","balance":"%s","balanceDate":"2026-08-16",
                                         "apr":"%s","minimumPayment":"%s","billId":%s}
                                        """
                                                .formatted(
                                                        name,
                                                        kind,
                                                        balance,
                                                        apr,
                                                        minimum,
                                                        billId == null
                                                                ? "null"
                                                                : "\"" + billId + "\"")),
                                201)
                        .stringField("id"));
    }

    /** A transaction with a payee, filed under a seeded category by name, or none. */
    protected UUID spend(
            ApiClient client,
            UUID account,
            String date,
            String amount,
            String payee,
            String category) {
        return UUID.fromString(
                ok(
                                client.post(
                                        "/api/transactions",
                                        """
                                        {"accountId":"%s","date":"%s","amount":"%s","payeeName":"%s",
                                         "categoryId":%s}
                                        """
                                                .formatted(
                                                        account,
                                                        date,
                                                        amount,
                                                        payee,
                                                        category == null
                                                                ? "null"
                                                                : "\""
                                                                        + seededCategoryId(category)
                                                                        + "\"")),
                                201)
                        .stringField("id"));
    }
}
