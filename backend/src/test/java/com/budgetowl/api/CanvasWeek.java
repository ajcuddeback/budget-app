package com.budgetowl.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * <b>The canvas's own week</b>, built through the real API, for the check-in tests and for the
 * fixtures the user guide is captured from — one builder, so the two cannot describe different
 * weeks (docs/features/weekly-check-in.md, Step 4: "the canvas figures reconcile").
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
 *   <li><b>Week 34</b> (Aug 17–23), on Friday the 21st: unbudgeted spending with the canvas's
 *       answers applied is 42.30 + 250.80 + 18.50 + 56.00 + 38.00 + 81.40 = <b>487.00</b> (Whole
 *       Foods answered Groceries drops out; the gym and Comcast are budgeted by their bills), so
 *       the surplus is <b>63</b>. {@code free = 1,529.01 − 917.44 = 611.57}; {@code held = 63};
 *       next week is 7 of 8 remaining days: {@code 7 × 548.57 ÷ 8 = 479.99875 → 480.00}, rolled
 *       over <b>543.00</b>. The emergency fund's 2,340 becomes <b>2,403</b>.
 * </ul>
 */
final class CanvasWeek {

    /** Sunday of week 33, mid-day: when the first check-in is finished. */
    static final Instant WEEK_33_SUNDAY = Instant.parse("2026-08-16T12:00:00Z");

    /** Friday of week 34, mid-day: the canvas's desktop artboard. */
    static final Instant WEEK_34_FRIDAY = Instant.parse("2026-08-21T12:00:00Z");

    static final String W33 = "/api/check-ins/2026-W33";
    static final String W34 = "/api/check-ins/2026-W34";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ApiClient owner;
    private final JdbcTemplate jdbc;
    private final Consumer<Instant> clock;
    private final String currency;

    /**
     * @param clock moves the application's clock; the week is seeded at two instants
     * @param currency the household's base currency: every account is opened in it
     */
    CanvasWeek(ApiClient owner, JdbcTemplate jdbc, Consumer<Instant> clock, String currency) {
        this.owner = owner;
        this.jdbc = jdbc;
        this.clock = clock;
        this.currency = currency;
    }

    /**
     * Week 33 completed with a target of 550, the late parking charge, and week 34's rows on Friday
     * the 21st with nothing answered yet. Leaves the clock at that Friday.
     *
     * @return the ids, by the names the tests and the fixtures use
     */
    Map<String, UUID> build() {
        Map<String, UUID> ids = new LinkedHashMap<>();
        clock.accept(WEEK_33_SUNDAY);

        UUID chase = account("Chase", "4412");
        ids.put("chase", chase);
        ids.put("rent", bill("Rent", "1650.00", 1, category("Rent or mortgage")));
        ids.put("car", bill("Car payment", "342.00", 24, null));
        ids.put("internet", bill("Internet", "71.99", 25, category("Phone and internet")));
        ids.put("visa", bill("Visa", "185.00", 28, null));
        ids.put("gym", bill("Gym", "42.00", 18, category("Health")));
        ids.put("northwind", income("Northwind Systems", "2620.00", 20, chase));
        ids.put("rivera", income("Rivera Design", "2000.00", 5, chase));
        ids.put(
                "groceries",
                id(
                        ok(
                                owner.post(
                                        "/api/envelopes",
                                        """
                                        {"name":"Groceries","period":"2026-08","amount":"600.00",
                                         "categoryId":"%s"}
                                        """
                                                .formatted(category("Groceries"))),
                                201)));
        UUID fund =
                id(
                        ok(
                                owner.post(
                                        "/api/goals",
                                        """
                                        {"name":"Emergency fund","targetAmount":"6000.00",
                                         "startingAmount":"2140.00","monthlyContribution":"200.00"}
                                        """),
                                201));
        ids.put("fund", fund);
        ok(
                owner.post(
                        "/api/goals/" + fund + "/contributions",
                        "{\"amount\":\"200.00\",\"date\":\"2026-07-15\"}"),
                201);
        ids.put(
                "visaDebt",
                debt("Visa •8823", "CREDIT_CARD", "6180.00", "24.99", "185.00", ids.get("visa")));
        ids.put(
                "carDebt",
                debt("Car loan", "INSTALLMENT", "10650.00", "6.90", "342.00", ids.get("car")));
        ok(
                owner.put(
                        "/api/debt-plan",
                        "{\"strategy\":\"AVALANCHE\",\"monthlyAmount\":\"645.00\",\"version\":0}"),
                200);
        ok(
                owner.put(
                        "/api/bills/" + ids.get("rent") + "/occurrences/2026-08-01",
                        "{\"status\":\"PAID\"}"),
                200);

        spend(chase, "2026-06-19", "-71.99", "COMCAST XFINITY", "Phone and internet");
        spend(chase, "2026-07-19", "-71.99", "COMCAST XFINITY", "Phone and internet");
        spend(chase, "2026-07-20", "2620.00", "Northwind Systems", "Salary");
        spend(chase, "2026-08-01", "-1650.00", "Landlord", "Rent or mortgage");
        spend(chase, "2026-08-03", "-120.44", "Tartine", "Eating Out");
        spend(chase, "2026-08-05", "2000.00", "Rivera Design", "Salary");
        spend(chase, "2026-08-06", "-210.00", "Whole Foods", "Groceries");
        spend(chase, "2026-08-09", "-230.00", "Target", "Shopping");

        // Week 33: the first check-in, nothing to allocate. Its completion sets week 34's target.
        ok(owner.put(W33, null), 201);
        JsonNode first = json(owner.get(W33));
        ok(
                owner.post(
                        W33 + "/complete",
                        "{\"version\":%d,\"basis\":\"%s\"}"
                                .formatted(
                                        first.get("version").asLong(),
                                        first.get("basis").asString())),
                200);
        BigDecimal target =
                jdbc.queryForObject(
                        "SELECT next_week_target FROM check_ins WHERE week_start = '2026-08-10'",
                        BigDecimal.class);
        if (target == null || target.compareTo(new BigDecimal("550.00")) != 0) {
            throw new AssertionError(
                    "week 33's next-week figure is not the canvas's 550: " + target);
        }

        spend(chase, "2026-08-14", "-80.00", "City Parking", "Transit");

        clock.accept(WEEK_34_FRIDAY);
        ids.put("tartine", spend(chase, "2026-08-17", "-42.30", "Tartine Bakery", "Eating Out"));
        ids.put("target", spend(chase, "2026-08-18", "-250.80", "Target", "Shopping"));
        ids.put(
                "gymCharge",
                spend(chase, "2026-08-18", "-126.00", "Mountain Strength Co", "Health"));
        ids.put(
                "comcast",
                spend(chase, "2026-08-19", "-71.99", "COMCAST XFINITY", "Phone and internet"));
        ids.put("blueBottle", spend(chase, "2026-08-19", "-18.50", "SQ *BLUE BOTTLE", null));
        ids.put("paycheck", spend(chase, "2026-08-20", "2620.00", "Northwind Systems", "Salary"));
        ids.put("wholeFoods", spend(chase, "2026-08-20", "-94.10", "WHOLE FOODS", null));
        ids.put("shell", spend(chase, "2026-08-20", "-56.00", "Shell", "Fuel"));
        ids.put("amc", spend(chase, "2026-08-21", "-38.00", "AMC Theatres", "Entertainment"));
        ids.put("ride", spend(chase, "2026-08-21", "-81.40", "UBER *TRIP", null));

        // A charge rule exists only because a completed check-in wrote one (ADR-0041). The canvas
        // household's gym mapping predates the canvas month, so it is seeded as stored state. It
        // is not a figure: every amount the week reports is still the application's arithmetic.
        jdbc.update(
                """
                INSERT INTO charge_rules (id, household_id, bill_id, payee_id, account_id)
                SELECT gen_random_uuid(), b.household_id, b.id, p.id, ?
                  FROM bills b JOIN payees p ON p.household_id = b.household_id
                 WHERE b.id = ? AND p.name = 'Mountain Strength Co'
                """,
                chase,
                ids.get("gym"));
        return ids;
    }

    /**
     * The canvas's own answers, in the order the user guide gives them: prepaid gym, three
     * categories (Blue Bottle remembered), Comcast mapped and remembered.
     *
     * @return each answer's key and body, in order
     */
    static Map<String, String> answers(
            Map<String, UUID> ids, java.util.function.Function<String, UUID> category) {
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("odd." + ids.get("gymCharge"), "{\"choice\":\"PREPAID\"}");
        answers.put(
                "cat." + ids.get("blueBottle"),
                "{\"categoryId\":\"%s\",\"choice\":\"CATEGORY\",\"remember\":true}"
                        .formatted(category.apply("Eating Out")));
        answers.put(
                "cat." + ids.get("wholeFoods"),
                "{\"categoryId\":\"%s\",\"choice\":\"CATEGORY\"}"
                        .formatted(category.apply("Groceries")));
        answers.put(
                "cat." + ids.get("ride"),
                "{\"categoryId\":\"%s\",\"choice\":\"CATEGORY\"}"
                        .formatted(category.apply("Transport")));
        answers.put(
                "chg." + ids.get("comcast"),
                "{\"billId\":\"%s\",\"choice\":\"ATTACH\",\"dueDate\":\"2026-08-25\",\"remember\":true}"
                        .formatted(ids.get("internet")));
        return answers;
    }

    // ------------------------------------------------------------------------------- helpers

    UUID category(String name) {
        return jdbc.queryForObject(
                "SELECT id FROM categories WHERE lower(name) = lower(?)", UUID.class, name);
    }

    private UUID account(String name, String lastFour) {
        return id(
                ok(
                        owner.post(
                                "/api/accounts",
                                """
                                {"name":"%s","lastFour":"%s","type":"CHECKING","currency":"%s",
                                 "openingBalance":"100.0000"}
                                """
                                        .formatted(name, lastFour, currency)),
                        201));
    }

    UUID bill(String name, String amount, int day, UUID categoryId) {
        return id(
                ok(
                        owner.post(
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
                        201));
    }

    private UUID income(String name, String amount, int day, UUID account) {
        return id(
                ok(
                        owner.post(
                                "/api/income",
                                """
                                {"name":"%s","amount":"%s",
                                 "schedule":{"kind":"MONTHLY","dayOfMonth":%d},"accountId":"%s"}
                                """
                                        .formatted(name, amount, day, account)),
                        201));
    }

    UUID debt(String name, String kind, String balance, String apr, String minimum, UUID billId) {
        return id(
                ok(
                        owner.post(
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
                                                billId == null ? "null" : "\"" + billId + "\"")),
                        201));
    }

    /** A transaction with a payee, filed under a seeded category by name, or none. */
    UUID spend(UUID account, String date, String amount, String payee, String categoryName) {
        return id(
                ok(
                        owner.post(
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
                                                categoryName == null
                                                        ? "null"
                                                        : "\"" + category(categoryName) + "\"")),
                        201));
    }

    static JsonNode json(ApiResponse response) {
        return JSON.readTree(response.body());
    }

    static ApiResponse ok(ApiResponse response, int status) {
        if (response.status() != status) {
            throw new AssertionError(
                    "expected " + status + ", got " + response.status() + ": " + response.body());
        }
        return response;
    }

    private static UUID id(ApiResponse response) {
        return UUID.fromString(response.stringField("id"));
    }
}
