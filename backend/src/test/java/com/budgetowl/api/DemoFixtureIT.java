package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Generates the debt fixtures the UI harness serves, <b>from the real stack</b>, and fails if the
 * committed copy has drifted from what the application would now return.
 *
 * <p><b>Why this exists.</b> `tools/ui/helpers/mock-api.ts` answers every `/api/**` request from
 * `tools/ui/fixtures/demo-data.ts`; there is no backend behind a capture run, by design (ADR-0013:
 * a capture physically cannot reach anyone's records). That is the right control and it has one
 * consequence nobody can see: whatever the fixtures say, the user guide documents. For accounts and
 * bills the fixtures are plain rows and a reviewer can check them by eye. A payoff plan is not — it
 * is two projections, a debt-free month, a total interest figure and a balance series per strategy,
 * all of them output of the solver. Hand-written, they would be <em>plausible numbers the
 * application does not produce</em>, and the guide would teach arithmetic that does not exist.
 *
 * <p>So the fixtures are generated: this test seeds the demo household's debts through the real
 * endpoints, reads the real responses back, canonicalises the ids, and compares the result to the
 * committed file. A backend change that alters any of those responses fails here rather than
 * quietly leaving the guide wrong.
 *
 * <p><b>Regenerating.</b> Run with {@code -Dfixtures.write=true} and commit the diff. Read it
 * first: a change here changes what the user guide says, and every screenshot that shows one of
 * these figures needs recapturing (`tools/userguide-check.sh` finds the stale ones).
 *
 * <p><b>The clock is fixed</b>, because every figure a projection carries is relative to today: a
 * payoff month, an estimated-months count, a balance-as-of date. Without this the generated file
 * would differ every day and the comparison would be noise. {@link #DEMO_TODAY} sits inside the
 * month the rest of the demo household lives in, so the story is coherent: the anchor is a
 * statement from this month, nothing is estimated, and no balance is stale.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DemoFixtureIT extends ApiTestBase {

    /**
     * The instant the demo household lives at — inside `2026-08`, the month every other fixture
     * uses. Mid-month and mid-day so no boundary rounding is load-bearing.
     */
    private static final Instant DEMO_TODAY = Instant.parse("2026-08-20T12:00:00Z");

    /**
     * The amounts the log-a-payment sheet offers without typing: the plan's allocation for each
     * debt, and that plus the sheet's one-tap fifty. Anything else the mock answers with a loud
     * 501, so a capture can never show a split nobody generated.
     */
    private static final Map<String, List<String>> PREVIEW_AMOUNTS =
            Map.of(
                    "deb-0001", List.of("445.00", "495.00"),
                    "deb-0002", List.of("342.00", "392.00"),
                    "deb-0003", List.of("118.00", "168.00"));

    /** Every order the three demo debts can take under "Your own order", rung 1 first. */
    private static final List<List<String>> CUSTOM_ORDERS =
            List.of(
                    List.of("deb-0001", "deb-0002", "deb-0003"),
                    List.of("deb-0001", "deb-0003", "deb-0002"),
                    List.of("deb-0002", "deb-0001", "deb-0003"),
                    List.of("deb-0002", "deb-0003", "deb-0001"),
                    List.of("deb-0003", "deb-0001", "deb-0002"),
                    List.of("deb-0003", "deb-0002", "deb-0001"));

    /** The day the statements the demo balances come from were true. */
    private static final LocalDate DEMO_STATEMENT_DATE = LocalDate.of(2026, 8, 20);

    /**
     * Where the Visa's history starts.
     *
     * <p>The card is seeded at an older statement and paid down, rather than conjured at its
     * current balance, so the detail page's history is a <b>real</b> one: six payments the
     * application split into interest and principal itself, and a statement correction the
     * application computed. A hand-written history would be six rows whose arithmetic nobody
     * checked, under a heading that says what the balance was after each one.
     */
    private static final LocalDate VISA_FIRST_STATEMENT = LocalDate.of(2026, 2, 20);

    /**
     * What the Visa owed in February.
     *
     * <p>Chosen so that six payments of $445 land near the $6,180 the designs show for August: the
     * confirmation then writes a small correction rather than a large one, which is the shape a
     * real statement reconciliation has. The exact figure is the application's, not this constant's
     * — {@code confirmBalance} absorbs whatever the difference turns out to be.
     */
    private static final String VISA_FEBRUARY_BALANCE = "8240.00";

    private static final Path COMMITTED =
            Path.of("..", "tools", "ui", "fixtures", "debt-api.generated.ts");

    /** Where a mismatching run writes what it would have committed. */
    private static final Path REGENERATED = Path.of("target", "debt-api.generated.ts");

    private static final Path COMMITTED_STATES =
            Path.of("..", "tools", "ui", "fixtures", "debt-states.generated.ts");

    private static final Path REGENERATED_STATES = Path.of("target", "debt-states.generated.ts");

    /**
     * A clock the test moves, rather than one fixed before the application starts.
     *
     * <p><b>Fixing it up front does not work</b>, and the reason is worth keeping: the demo
     * household lives in August 2026, so a clock fixed there is in the real past, and {@code
     * household_invitations} takes its {@code created_at} from the database's {@code now()} while
     * its {@code expires_at} comes from this clock. Seeding then inserts a row that expires before
     * it was created, the {@code CHECK} refuses it, and the fixture generator fails in {@code
     * seedHousehold} with a {@code 409} that has nothing to do with debts.
     *
     * <p>So: seed at real time, then move the clock to the demo instant and read. Every figure a
     * projection carries is relative to "now" at the moment of the read, which is the only place
     * the clock has to be still.
     */
    static final class SettableClock extends Clock {

        private volatile Instant instant = Instant.now();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void moveTo(Instant when) {
            this.instant = when;
        }
    }

    @Autowired private SettableClock clock;

    @TestConfiguration
    static class DemoClock {

        /**
         * Overrides {@code CoreConfig.clock()} for this context only.
         *
         * <p>Costs a second Spring context for one test class, which is the price of a generated
         * file that does not change when the date does.
         */
        @Bean
        @Primary
        SettableClock demoClock() {
            return new SettableClock();
        }
    }

    @Test
    void the_committed_debt_fixtures_are_what_the_application_returns() throws IOException {
        clock.moveTo(Instant.now());
        Household household = seedDemoHousehold();
        Fixture seeded = seedTheDemoDebts(household);

        // Nothing to normalise: the demo household lives in a month that is already in the past,
        // so every date it enters can be written literally and the API accepts it. Only the
        // DERIVED dates need the clock — payoff months, the debt-free month, the estimated-months
        // count — so it moves once, here, after everything is stored.
        clock.moveTo(DEMO_TODAY);

        Map<String, String> generated = new LinkedHashMap<>();
        generated.put("GET /api/debts", household.owner().get("/api/debts").body());
        generated.put("GET /api/debt-plan", household.owner().get("/api/debt-plan").body());
        generated.put(
                "GET /api/debt-plan/projection?strategy=SNOWBALL",
                household
                        .owner()
                        .get("/api/debt-plan/projection?strategy=SNOWBALL&monthlyAmount=905.00")
                        .body());
        // The what-if the page actually asks: the plan's amount plus fifty. It has to be a REAL
        // projection at 955 — serving the 905 plan back makes the page say "adding $50 a month
        // would not move the date", which is false and looks like an answer.
        generated.put(
                "GET /api/debt-plan/projection?monthlyAmount=955.00",
                household.owner().get("/api/debt-plan/projection?monthlyAmount=955.00").body());
        for (Map.Entry<String, UUID> debt : seeded.debts().entrySet()) {
            generated.put(
                    "GET /api/debts/" + debt.getKey(),
                    household.owner().get("/api/debts/" + debt.getValue()).body());
            generated.put(
                    "GET /api/debts/" + debt.getKey() + "/payments",
                    household.owner().get("/api/debts/" + debt.getValue() + "/payments").body());
        }

        // What the log-a-payment sheet asks before anything is recorded: each debt at the amount
        // the plan gives it, and at that plus the sheet's one-tap fifty. Generated rather than
        // written, because these are the interest/principal splits the sheet will show — a
        // hand-written one would be the browser-side arithmetic the preview endpoint exists to
        // avoid, moved into a fixture.
        for (Map.Entry<String, UUID> debt : seeded.debts().entrySet()) {
            for (String amount : PREVIEW_AMOUNTS.get(debt.getKey())) {
                generated.put(
                        "POST /api/debts/" + debt.getKey() + "/payments/preview " + amount,
                        household
                                .owner()
                                .post(
                                        "/api/debts/" + debt.getValue() + "/payments/preview",
                                        "{\"amount\":\"" + amount + "\"}")
                                .body());
            }
        }

        // LAST, because it changes every rung and every date the reads above captured: the
        // household's own order, in EVERY order three debts can take. The page sends whatever
        // order the household's moves produce — the avalanche order when Custom is first chosen,
        // then each intermediate order one move at a time — and a fixture holding only one of
        // them would leave the mock either refusing the rest or answering them with figures for
        // an order nobody chose. Six is few enough to generate outright.
        int change = 0;
        for (List<String> order : CUSTOM_ORDERS) {
            // A second later each time. Every real plan change writes a snapshot, and
            // ux_payoff_plan_snapshots_household_taken_at_currency is unique on the instant it was
            // taken — so with the clock frozen, the second change collided with the first and came
            // back a 409. In production taken_at is the system clock at microsecond precision, and
            // a second change that fast would be refused as stale-version first. Seconds move no
            // date the plan depends on; only the month does.
            clock.moveTo(DEMO_TODAY.plusSeconds(++change));
            long version = household.owner().get("/api/debt-plan").numberField("version");
            StringBuilder ids = new StringBuilder();
            for (String name : order) {
                ids.append(ids.isEmpty() ? "" : ",")
                        .append('"')
                        .append(seeded.debts().get(name))
                        .append('"');
            }
            ApiResponse custom =
                    household
                            .owner()
                            .put(
                                    "/api/debt-plan",
                                    "{\"strategy\":\"CUSTOM\",\"customOrder\":["
                                            + ids
                                            + "],\"version\":"
                                            + version
                                            + "}");
            if (custom.status() != 200) {
                throw new AssertionError("could not set a demo custom order: " + custom.body());
            }
            String key = String.join(",", order);
            generated.put("PUT /api/debt-plan CUSTOM " + key, custom.body());
            generated.put(
                    "GET /api/debts CUSTOM " + key, household.owner().get("/api/debts").body());
            // The page's "+$50 a month" line under THIS order. The server projects the stored
            // order, so the what-if for a household's own order is its own figure; serving the
            // avalanche what-if put "4 months sooner" under a plan that was not avalanche.
            generated.put(
                    "GET /api/debt-plan/projection?monthlyAmount=955.00 CUSTOM " + key,
                    household.owner().get("/api/debt-plan/projection?monthlyAmount=955.00").body());
        }

        String document = canonicalise(render(generated, "DEBT_API"), seeded);

        compareOrWrite(document, COMMITTED, REGENERATED);
    }

    /**
     * The two balance states the demo household does not have, and could not be given.
     *
     * <p>All three demo debts are {@code CONFIRMED}, because they are anchored to the statement the
     * guide's figures come from. That leaves the screens that say <em>"this number is a guess"</em>
     * — the estimated wording, the stale wording, and the whole confirm-the-estimate sheet —
     * rendered by nothing. They are the screens where being wrong matters most, so they get their
     * own scenario rather than a hand-written fixture: an estimated balance is the anchor rolled
     * forward <b>with interest</b>, and inventing one is inventing arithmetic.
     *
     * <p>Separate household, separate file, separate test, so the demo figures the guide captures
     * cannot be perturbed by it — adding a fourth debt would move {@code owed}, {@code debtCount}
     * and every date in the plan.
     */
    @Test
    void the_committed_balance_state_fixtures_are_what_the_application_returns()
            throws IOException {
        clock.moveTo(Instant.now());
        Household household = seedDemoHousehold();

        // Three months since the statement and nothing logged since: ESTIMATED, and the figure on
        // screen is larger than the anchor because three months of interest are in it.
        UUID estimated =
                createDemoDebt(
                        household,
                        "Store card",
                        "CREDIT_CARD",
                        "Argos",
                        "1240.00",
                        "29.900",
                        "45.00",
                        null,
                        DEMO_STATEMENT_DATE.minusMonths(3).toString(),
                        null);
        // Past MAXIMUM_ESTIMATED_MONTHS: the screen stops extrapolating and asks for a statement
        // rather than showing a year and a half of compounding as if it knew.
        UUID stale =
                createDemoDebt(
                        household,
                        "Old overdraft",
                        "CREDIT_CARD",
                        "Monzo",
                        "860.00",
                        "39.900",
                        "25.00",
                        null,
                        DEMO_STATEMENT_DATE.minusMonths(14).toString(),
                        null);

        clock.moveTo(DEMO_TODAY);

        Map<UUID, String> names = new LinkedHashMap<>();
        names.put(estimated, "deb-9001");
        names.put(stale, "deb-9002");
        Fixture seeded = new Fixture(Map.of(), null, names);

        Map<String, String> generated = new LinkedHashMap<>();
        generated.put("GET /api/debts", household.owner().get("/api/debts").body());
        generated.put(
                "GET /api/debts/deb-9001", household.owner().get("/api/debts/" + estimated).body());
        generated.put(
                "GET /api/debts/deb-9002", household.owner().get("/api/debts/" + stale).body());

        compareOrWrite(
                canonicalise(render(generated, "DEBT_STATES"), seeded),
                COMMITTED_STATES,
                REGENERATED_STATES);
    }

    // ---------------------------------------------------------------------------- goals (slice 11)

    private static final Path COMMITTED_GOALS =
            Path.of("..", "tools", "ui", "fixtures", "goal-api.generated.ts");

    private static final Path REGENERATED_GOALS = Path.of("target", "goal-api.generated.ts");

    /**
     * The preview bodies the goal dialogs send, per goal, in the canonical form the mock keys them
     * by (keys in alphabetical order). The plan dialog opens with the plan in force and the guide
     * types one other figure; the edit dialog asks once on opening with nothing changed, and once
     * more for the target the guide types. Anything else the mock answers with a loud 501, so a
     * capture can never show a projection nobody generated.
     */
    private static final Map<String, List<String>> GOAL_PREVIEWS =
            Map.of(
                    "gol-0001",
                    List.of(
                            "{}",
                            "{\"monthlyAmount\":\"110\"}",
                            "{\"monthlyAmount\":\"165\"}",
                            "{\"targetAmount\":\"4000\"}"),
                    "gol-0002",
                    List.of("{}", "{\"monthlyAmount\":\"200\"}", "{\"monthlyAmount\":\"250\"}"),
                    "gol-0003",
                    List.of("{}", "{\"monthlyAmount\":\"0\"}"));

    /**
     * What the add dialog asks as the guide fills it in — the target, then the month, then the
     * monthly amount — each state it can settle in on the way.
     */
    private static final List<String> DRAFT_PREVIEWS =
            List.of(
                    "{\"targetAmount\":\"900\"}",
                    "{\"targetAmount\":\"900\",\"targetMonth\":\"2027-06\"}",
                    "{\"monthlyAmount\":\"75\",\"targetAmount\":\"900\",\"targetMonth\":\"2027-06\"}");

    /**
     * The demo household's goals, <b>from the real application</b>: every saved figure, every
     * projected month, every required monthly amount and the fifth term of the period summary.
     *
     * <p>The canvas's own figures do not reconcile ({@code docs/features/goals.md}, "Where the
     * designs and the docs disagree"), so the goals are seeded from its <em>shape</em> — Japan,
     * $890 of $3,500 by November 2027 at $110 a month, with Owl's $165 from September; an emergency
     * fund part-funded on day one — and the arithmetic is the application's.
     *
     * <p><b>The clock moves to the demo instant before the goals are seeded</b>, unlike the debts:
     * a plan step cannot be set in a month that has already passed, which is the property decision
     * 4 exists for, so August's plan has to be written in August. The demo debts are seeded first,
     * at real time as the debt scenario does, because the period summary's plan extra reads them.
     */
    @Test
    void the_committed_goal_fixtures_are_what_the_application_returns() throws IOException {
        clock.moveTo(Instant.now());
        Household household = seedDemoHousehold();
        Fixture debts = seedTheDemoDebts(household);
        UUID savings =
                UUID.fromString(
                        household
                                .owner()
                                .post(
                                        "/api/accounts",
                                        """
                                        {"name":"Emergency Fund","type":"SAVINGS","currency":"USD",
                                         "openingBalance":"0.00"}
                                        """)
                                .stringField("id"));

        clock.moveTo(DEMO_TODAY);
        Map<UUID, String> stable = new LinkedHashMap<>(debts.stableIds());
        stable.put(savings, "acc-0002");
        Map<String, UUID> goals = seedTheDemoGoals(household, savings, stable);

        Map<String, String> generated = new LinkedHashMap<>();
        generated.put(
                "GET /api/goals", household.owner().get("/api/goals?includeArchived=true").body());
        for (Map.Entry<String, UUID> goal : goals.entrySet()) {
            String path = "/api/goals/" + goal.getValue();
            generated.put("GET /api/goals/" + goal.getKey(), household.owner().get(path).body());
            generated.put(
                    "GET /api/goals/" + goal.getKey() + "/contribution-plan",
                    household.owner().get(path + "/contribution-plan").body());
            generated.put(
                    "GET /api/goals/" + goal.getKey() + "/contributions",
                    household.owner().get(path + "/contributions").body());
        }
        for (Map.Entry<String, List<String>> previews : new TreeMap<>(GOAL_PREVIEWS).entrySet()) {
            for (String body : previews.getValue()) {
                generated.put(
                        "POST /api/goals/"
                                + previews.getKey()
                                + "/projection-preview "
                                + previewLabel(body),
                        household
                                .owner()
                                .post(
                                        "/api/goals/"
                                                + goals.get(previews.getKey())
                                                + "/projection-preview",
                                        body)
                                .body());
            }
        }
        for (String body : DRAFT_PREVIEWS) {
            generated.put(
                    "POST /api/goals/projection-preview " + previewLabel(body),
                    household.owner().post("/api/goals/projection-preview", body).body());
        }
        // The goal terms of August's summary, which demo-data.ts folds into the bills fixtures'
        // own summary. Only goalsSetAside, goalsCounted and debtPlanExtra are read from it: the
        // income, bills and envelopes of this household are not the demo household's.
        generated.put(
                "GET /api/periods/2026-08/summary",
                household.owner().get("/api/periods/2026-08/summary").body());

        String document =
                pinTimestamps(
                        canonicalise(
                                render(generated, "GOAL_API"),
                                new Fixture(Map.of(), null, stable)));
        compareOrWrite(document, COMMITTED_GOALS, REGENERATED_GOALS);
    }

    /**
     * Four goals: three active, one archived.
     *
     * <ul>
     *   <li><b>Japan</b> — the designs' goal: $3,500 by November 2027, $890 saved over seven
     *       months, $110 a month from August and Owl's $165 from September, so the page shows a
     *       scheduled step and the plan's effective months.
     *   <li><b>Emergency fund</b> — no month, $2,000 already saved on day one, kept in the savings
     *       account, with a withdrawal for a vet bill so the history shows one.
     *   <li><b>New laptop</b> — reached and over-funded: the bar is full and the figure says the
     *       real amount.
     *   <li><b>Wedding gift</b> — archived, so the list's archived section has something in it.
     * </ul>
     */
    private Map<String, UUID> seedTheDemoGoals(
            Household household, UUID savings, Map<UUID, String> stable) {
        Map<String, UUID> goals = new LinkedHashMap<>();
        UUID japan =
                createDemoGoal(
                        household,
                        """
                        {"name":"Japan","targetAmount":"3500.00","targetMonth":"2027-11",
                         "monthlyContribution":"110.00"}
                        """);
        String[][] japanMonths = {
            {"2026-01-15", "120.00"},
            {"2026-02-15", "120.00"},
            {"2026-03-15", "130.00"},
            {"2026-04-15", "130.00"},
            {"2026-05-15", "130.00"},
            {"2026-06-15", "130.00"},
            {"2026-07-15", "130.00"}
        };
        for (String[] month : japanMonths) {
            contributeToDemoGoal(household, japan, month[0], month[1], null);
        }
        long version = household.owner().get("/api/goals/" + japan).numberField("version");
        ApiResponse raised =
                household
                        .owner()
                        .put(
                                "/api/goals/" + japan + "/contribution-plan/2026-09",
                                "{\"monthlyAmount\":\"165.00\",\"version\":" + version + "}");
        if (raised.status() != 200) {
            throw new AssertionError("could not raise the demo plan: " + raised.body());
        }

        UUID fund =
                createDemoGoal(
                        household,
                        """
                        {"name":"Emergency fund","targetAmount":"6000.00","startingAmount":"2000.00",
                         "monthlyContribution":"200.00","accountId":"%s"}
                        """
                                .formatted(savings));
        contributeToDemoGoal(household, fund, "2026-06-01", "200.00", null);
        contributeToDemoGoal(household, fund, "2026-07-01", "200.00", null);
        contributeToDemoGoal(household, fund, "2026-07-18", "-160.00", "Vet bill");
        contributeToDemoGoal(household, fund, "2026-08-03", "100.00", null);

        UUID laptop =
                createDemoGoal(
                        household,
                        """
                        {"name":"New laptop","targetAmount":"1200.00","targetMonth":"2026-10"}
                        """);
        for (int month = 3; month <= 7; month++) {
            contributeToDemoGoal(
                    household, laptop, LocalDate.of(2026, month, 10).toString(), "250.00", null);
        }

        UUID gift =
                createDemoGoal(
                        household,
                        """
                        {"name":"Wedding gift","targetAmount":"300.00","startingAmount":"300.00"}
                        """);
        ApiResponse archived = household.owner().post("/api/goals/" + gift + "/archive", null);
        if (archived.status() != 200) {
            throw new AssertionError("could not archive the demo goal: " + archived.body());
        }

        goals.put("gol-0001", japan);
        goals.put("gol-0002", fund);
        goals.put("gol-0003", laptop);
        goals.put("gol-0004", gift);
        for (Map.Entry<String, UUID> goal : goals.entrySet()) {
            stable.put(goal.getValue(), goal.getKey());
        }
        return goals;
    }

    private UUID createDemoGoal(Household household, String body) {
        ApiResponse created = household.owner().post("/api/goals", body);
        if (created.status() != 201) {
            throw new AssertionError("could not seed a demo goal: " + created.body());
        }
        return UUID.fromString(created.stringField("id"));
    }

    private void contributeToDemoGoal(
            Household household, UUID goal, String date, String amount, String note) {
        ApiResponse recorded =
                household
                        .owner()
                        .post(
                                "/api/goals/" + goal + "/contributions",
                                "{\"amount\":\"%s\",\"date\":\"%s\"%s}"
                                        .formatted(
                                                amount,
                                                date,
                                                note == null ? "" : ",\"note\":\"" + note + "\""));
        if (recorded.status() != 201) {
            throw new AssertionError("could not seed a demo contribution: " + recorded.body());
        }
    }

    /**
     * Pins every {@code createdAt} and {@code updatedAt} to the demo instant.
     *
     * <p>Hibernate stamps those from the JVM's own clock rather than the application's, so they are
     * the one part of a goal response the settable clock cannot fix, and leaving them would make
     * the drift check fail on every run for a difference that is not one. No figure depends on
     * them; the history's order is by date first.
     */
    /**
     * A preview body as the fixture key names it: {@code monthlyAmount=75&targetAmount=900}, or
     * {@code unchanged} for an empty one. Quote-free, because {@link #render} writes keys as they
     * are, and the mock builds the same label from the body it receives.
     */
    private static String previewLabel(String body) {
        String label = body.replaceAll("[{}\"]", "").replace(':', '=').replace(',', '&');
        return label.isEmpty() ? "unchanged" : label;
    }

    private static String pinTimestamps(String document) {
        return document.replaceAll(
                "\"(createdAt|updatedAt)\":\"[^\"]+\"", "\"$1\":\"" + DEMO_TODAY + "\"");
    }

    /**
     * Writes the fixture, or fails because the committed one no longer matches.
     *
     * <p>Shared by both scenarios: the demo household the guide is captured from, and the balance
     * states that household deliberately does not have.
     */
    private static void compareOrWrite(String document, Path committed, Path regenerated)
            throws IOException {
        if (Boolean.getBoolean("fixtures.write")) {
            Files.createDirectories(committed.getParent());
            Files.writeString(committed, document);
            return;
        }

        if (!Files.exists(committed)) {
            Files.createDirectories(regenerated.getParent());
            Files.writeString(regenerated, document);
            throw new AssertionError(
                    "These fixtures have never been generated. What this run would have committed"
                            + " is in backend/"
                            + regenerated
                            + " — copy it to "
                            + committed.normalize()
                            + ", or re-run with -Dfixtures.write=true.");
        }

        String stored = Files.readString(committed);
        if (!stored.equals(document)) {
            Files.createDirectories(regenerated.getParent());
            Files.writeString(regenerated, document);
        }
        assertThat(document)
                .as(
                        "The fixtures the UI harness serves no longer match what the application"
                                + " returns, so the user guide is documenting arithmetic this code"
                                + " does not do. What this run produced is in backend/"
                                + regenerated
                                + ". Read the diff, re-run with -Dfixtures.write=true, and"
                                + " recapture the guide screenshots that show these figures.")
                .isEqualTo(stored);
    }

    /**
     * The demo household, in <b>USD</b>.
     *
     * <p>{@code ApiTestBase.seedHousehold} creates a GBP household, which is right for the
     * authorization suite and wrong here: every other fixture in {@code demo-data.ts} is USD, and a
     * debt page showing £ beside an accounts page showing $ is a guide screenshot nobody can use.
     * Same shape, one field different.
     */
    private Household seedDemoHousehold() {
        ApiResponse setup =
                browser()
                        .post(
                                "/api/setup/first-user",
                                """
                                {"email":"%s","displayName":"Ada","password":"%s",
                                 "householdName":"Rivera Household","baseCurrency":"USD"}
                                """
                                        .formatted(OWNER_EMAIL, OWNER_SECRET));
        if (setup.status() != 201) {
            throw new AssertionError("could not set up the demo household: " + setup.body());
        }
        ApiClient owner = signIn(OWNER_EMAIL, OWNER_SECRET);
        acceptInvitation(invite(owner, MEMBER_EMAIL, "MEMBER"), "Grace", MEMBER_SECRET);
        acceptInvitation(invite(owner, VIEWER_EMAIL, "VIEWER"), "Alan", VIEWER_SECRET);
        return new Household(
                owner, signIn(MEMBER_EMAIL, MEMBER_SECRET), signIn(VIEWER_EMAIL, VIEWER_SECRET));
    }

    /** The ids the generator creates, and the stable names the fixtures know them by. */
    private record Fixture(Map<String, UUID> debts, UUID bill, Map<UUID, String> stableIds) {}

    /**
     * The designs' own three debts, so the guide and the canvas agree.
     *
     * <p>Balances 6,180 + 10,650 + 9,650 = <b>26,480</b>, minimums 185 + 342 + 118 = <b>645</b>,
     * and the car loan's 342 is a bill row — which is what makes the counted-once rule visible on
     * the Bills &amp; Income page rather than merely true.
     */
    private Fixture seedTheDemoDebts(Household household) {
        Map<UUID, String> stable = new LinkedHashMap<>();
        String statementDate = DEMO_STATEMENT_DATE.toString();

        UUID carPaymentBill =
                UUID.fromString(
                        household
                                .owner()
                                .post(
                                        "/api/bills",
                                        """
                                        {"name":"Car payment","expectedAmount":"342.00",
                                         "schedule":{"kind":"MONTHLY","dayOfMonth":5}}
                                        """)
                                .stringField("id"));
        stable.put(carPaymentBill, "bil-0008");

        // The Visa is seeded at its FEBRUARY statement and paid down, so the detail page's
        // history is one the application produced rather than one somebody typed.
        UUID visa =
                createDemoDebt(
                        household,
                        "Visa",
                        "CREDIT_CARD",
                        "Chase",
                        VISA_FEBRUARY_BALANCE,
                        "22.900",
                        "185.00",
                        "10650.00",
                        VISA_FIRST_STATEMENT.toString(),
                        null);
        payDownTheVisa(household, visa);
        UUID carLoan =
                createDemoDebt(
                        household,
                        "Car loan",
                        "INSTALLMENT",
                        "Ally",
                        "10650.00",
                        "6.400",
                        "342.00",
                        "18400.00",
                        statementDate,
                        carPaymentBill);
        UUID student =
                createDemoDebt(
                        household,
                        "Student loan",
                        "INSTALLMENT",
                        "Nelnet",
                        "9650.00",
                        "4.500",
                        "118.00",
                        "12000.00",
                        statementDate,
                        null);
        stable.put(visa, "deb-0001");
        stable.put(carLoan, "deb-0002");
        stable.put(student, "deb-0003");

        // "$645/mo committed · $560 of it strategic" — the designs' header strip.
        long version = household.owner().get("/api/debt-plan").numberField("version");
        household
                .owner()
                .put(
                        "/api/debt-plan",
                        "{\"strategy\":\"AVALANCHE\",\"monthlyAmount\":\"905.00\",\"version\":"
                                + version
                                + "}");

        Map<String, UUID> debts = new LinkedHashMap<>();
        debts.put("deb-0001", visa);
        debts.put("deb-0002", carLoan);
        debts.put("deb-0003", student);
        return new Fixture(debts, carPaymentBill, stable);
    }

    /**
     * Six months of payments, and then the statement that settles what they came to.
     *
     * <p>Every figure in the resulting history — each split into interest and principal, each
     * closing balance, and the size of the correction — is the application's own. That is the
     * point: the user guide's payment history is a screenshot of real arithmetic, and the
     * compounding sentence printed beneath it is therefore true of the rows above it.
     *
     * <p>The correction is written by confirming the August statement at the figure the designs
     * show. It is a {@code STATEMENT_CORRECTION} rather than a payment, which is what the
     * provenance column on every row exists to keep apart: an inferred figure must never be shown
     * as one somebody asserted.
     */
    private void payDownTheVisa(Household household, UUID visa) {
        for (int month = 3; month <= 8; month++) {
            LocalDate paidOn = LocalDate.of(2026, month, 20);
            ApiResponse logged =
                    household
                            .owner()
                            .post(
                                    "/api/debts/" + visa + "/payments",
                                    """
                                    {"paidDate":"%s","amount":"445.00"}
                                    """
                                            .formatted(paidOn));
            if (logged.status() != 201) {
                throw new AssertionError("could not log a demo payment: " + logged.body());
            }
        }

        ApiResponse confirmed =
                household
                        .owner()
                        .post(
                                "/api/debts/" + visa + "/balance",
                                """
                                {"balance":"6180.00","balanceDate":"%s",
                                 "note":"Statement from Chase"}
                                """
                                        .formatted(DEMO_STATEMENT_DATE));
        if (confirmed.status() != 200) {
            throw new AssertionError("could not confirm the demo balance: " + confirmed.body());
        }
    }

    private UUID createDemoDebt(
            Household household,
            String name,
            String kind,
            String servicer,
            String balance,
            String apr,
            String minimum,
            String originalBalance,
            String balanceDate,
            UUID billId) {
        ApiResponse created =
                household
                        .owner()
                        .post(
                                "/api/debts",
                                """
                                {"name":"%s","kind":"%s","servicer":"%s","balance":"%s",
                                 "balanceDate":"%s","apr":"%s","minimumPayment":"%s",
                                 "originalBalance":%s,"billId":%s}
                                """
                                        .formatted(
                                                name,
                                                kind,
                                                servicer,
                                                balance,
                                                balanceDate,
                                                apr,
                                                minimum,
                                                originalBalance == null
                                                        ? "null"
                                                        : "\"" + originalBalance + "\"",
                                                billId == null ? "null" : "\"" + billId + "\""));
        if (created.status() != 201) {
            throw new AssertionError("could not seed a demo debt: " + created.body());
        }
        return UUID.fromString(created.stringField("id"));
    }

    // ------------------------------------------------------------ weekly check-in (slice 14)

    private static final Path COMMITTED_CHECK_INS =
            Path.of("..", "tools", "ui", "fixtures", "check-in-api.generated.ts");

    private static final Path REGENERATED_CHECK_INS =
            Path.of("target", "check-in-api.generated.ts");

    /**
     * The demo categories' stable ids, by the names the seeded defaults share with them. The
     * category picker on the check-in page lists {@code DEMO_CATEGORIES}, so an answer the guide
     * gives carries one of these ids, and the generated keys have to carry the same one.
     */
    private static final Map<String, String> DEMO_CATEGORY_IDS =
            Map.of(
                    "Income", "cat-0001",
                    "Salary", "cat-0002",
                    "Bills", "cat-0003",
                    "Utilities", "cat-0005",
                    "Groceries", "cat-0006",
                    "Transport", "cat-0007",
                    "Eating Out", "cat-0008");

    /**
     * The canvas's own week (docs/features/weekly-check-in.md), <b>from the real application</b>,
     * as the user guide walks it: not started, started, each answer in the guide's order, the
     * surplus to the emergency fund, finished — and the rule lists and history after.
     *
     * <p>Built by {@link CanvasWeek}, the same builder {@code CheckInIT} asserts the canvas's
     * figures against, so the guide and the test cannot describe two different weeks. Every figure
     * here — 550, 487, 63, 480, 543, 2,340 → 2,403 — is the application's arithmetic.
     *
     * <p>Keys carry each request's body in the form the mock rebuilds from what it receives (sorted
     * keys, {@code k=v&…}), so a capture can only show a response this run produced, and anything
     * else is a loud 501.
     */
    @Test
    void the_committed_check_in_fixtures_are_what_the_application_returns() throws IOException {
        clock.moveTo(Instant.now());
        Household household = seedDemoHousehold();
        ApiClient owner = household.owner();
        Map<String, UUID> ids = new CanvasWeek(owner, jdbc, clock::moveTo, "USD").build();
        Map<UUID, String> stable = new LinkedHashMap<>();
        for (Map.Entry<String, String> category : new TreeMap<>(DEMO_CATEGORY_IDS).entrySet()) {
            stable.put(seededCategoryId(category.getKey()), category.getValue());
        }

        Map<String, String> generated = new LinkedHashMap<>();
        generated.put("GET /api/check-ins/current", owner.get("/api/check-ins/current").body());
        generated.put(
                "GET /api/check-ins/current VIEWER",
                household.viewer().get("/api/check-ins/current").body());
        generated.put("PUT /api/check-ins/2026-W34", owner.put(CanvasWeek.W34, null).body());
        // Bodies carry the real ids; canonicalising maps each seeded category to its demo id, in
        // the keys and the responses alike.
        Map<String, String> answers = CanvasWeek.answers(ids, this::seededCategoryId);
        answers.put(
                "alloc",
                "{\"amount\":\"63.00\",\"choice\":\"GOAL\",\"goalId\":\"%s\"}"
                        .formatted(ids.get("fund")));
        for (Map.Entry<String, String> answer : answers.entrySet()) {
            String body = answer.getValue();
            if (answer.getKey().startsWith("cat.") && body.contains(",\"remember\":true")) {
                // The page asks for a category first and the "Always file" tick second: two
                // answers, the second replacing the first at its version. Both are generated.
                putAnswer(
                        owner, generated, answer.getKey(), body.replace(",\"remember\":true", ""));
                body = body.substring(0, body.length() - 1) + ",\"version\":0}";
            }
            putAnswer(owner, generated, answer.getKey(), body);
        }
        generated.put(
                "GET /api/check-ins/2026-W34 VIEWER",
                household.viewer().get(CanvasWeek.W34).body());
        tools.jackson.databind.JsonNode week = CanvasWeek.json(owner.get(CanvasWeek.W34));
        ApiResponse completed =
                owner.post(
                        CanvasWeek.W34 + "/complete",
                        "{\"version\":%d,\"basis\":\"%s\"}"
                                .formatted(
                                        week.get("version").asLong(),
                                        week.get("basis").asString()));
        if (completed.status() != 200) {
            throw new AssertionError("could not complete the demo week: " + completed.body());
        }
        generated.put("POST /api/check-ins/2026-W34/complete", completed.body());
        generated.put("GET /api/check-ins", owner.get("/api/check-ins").body());
        generated.put("GET /api/payee-rules", owner.get("/api/payee-rules").body());
        generated.put("GET /api/charge-rules", owner.get("/api/charge-rules").body());

        String document =
                pinBases(
                        pinTimestamps(
                                canonicalise(
                                        render(generated, "CHECK_IN_API"),
                                        new Fixture(Map.of(), null, stable))));
        compareOrWrite(document, COMMITTED_CHECK_INS, REGENERATED_CHECK_INS);
    }

    private static void putAnswer(
            ApiClient owner, Map<String, String> generated, String itemKey, String body) {
        ApiResponse answered = owner.put(CanvasWeek.W34 + "/answers/" + itemKey, body);
        if (answered.status() != 200) {
            throw new AssertionError("could not answer the demo week: " + answered.body());
        }
        generated.put(
                "PUT /api/check-ins/2026-W34/answers/" + itemKey + " " + checkInLabel(body),
                answered.body());
    }

    /**
     * An answer body as the mock keys it: {@link #previewLabel} without the {@code version} a
     * replacement carries or any {@code false}, which the page sends and the generator need not.
     */
    private static String checkInLabel(String body) {
        return java.util.Arrays.stream(previewLabel(body).split("&"))
                .filter(part -> !part.startsWith("version=") && !part.endsWith("=false"))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    /**
     * Replaces each distinct basis with a stable stand-in of the same shape. A basis is a digest
     * over the week, ids included, so it differs every run although nothing a person sees does.
     */
    private static String pinBases(String document) {
        java.util.regex.Matcher bases =
                java.util.regex.Pattern.compile("\"basis\":\"([0-9a-f]{64})\"").matcher(document);
        Map<String, String> pinned = new LinkedHashMap<>();
        while (bases.find()) {
            pinned.computeIfAbsent(
                    bases.group(1), digest -> String.format("%064d", pinned.size() + 1));
        }
        String out = document;
        for (Map.Entry<String, String> basis : pinned.entrySet()) {
            out = out.replace(basis.getKey(), basis.getValue());
        }
        return out;
    }

    // ---------------------------------------------------------------- reports (slice 13)

    private static final Path COMMITTED_REPORTS =
            Path.of("..", "tools", "ui", "fixtures", "report-api.generated.ts");

    private static final Path REGENERATED_REPORTS = Path.of("target", "report-api.generated.ts");

    private static final Path COMMITTED_REPORT_STATES =
            Path.of("..", "tools", "ui", "fixtures", "report-states.generated.ts");

    private static final Path REGENERATED_REPORT_STATES =
            Path.of("target", "report-states.generated.ts");

    /** The three ranges the Insights chips ask for on 20 August 2026, by the device's date. */
    private static final List<String> REPORT_RANGES =
            List.of(
                    "from=2025-09&to=2026-08",
                    "from=2026-03&to=2026-08",
                    "from=2026-06&to=2026-08");

    /**
     * The demo household's ledger as the reports sum it: <b>the August rows of {@code
     * DEMO_TRANSACTIONS}, row for row</b>, and eleven months of history before them.
     *
     * <p>{@code date, amount, account, category, status}. The account and category are the stable
     * ids {@code demo-data.ts} knows them by. {@code demo-data.ts} checks at load that every one of
     * its own transactions is in the ledger this writes out, so editing a fixture row without
     * regenerating fails every UI run rather than leaving Insights summing a different ledger from
     * the one the transactions screen shows.
     *
     * <p>Shaped to have something to say: salary rising in April, a December bonus, winter
     * utilities, two refunds (a duplicate transit pass, a grocery return), spending under the
     * now-archived Wedding category in May and June, and transfers that must change nothing. The
     * one uncategorised row is August's pending grocery shop.
     */
    static final String[][] DEMO_LEDGER = {
        // September 2025
        {"2025-09-01", "3050.00", "acc-0001", "cat-0002", "CLEARED"},
        {"2025-09-01", "-1400.00", "acc-0001", "cat-0004", "CLEARED"},
        {"2025-09-04", "-112.35", "acc-0001", "cat-0006", "CLEARED"},
        {"2025-09-05", "-62.00", "acc-0003", "cat-0007", "CLEARED"},
        {"2025-09-12", "-38.50", "acc-0003", "cat-0008", "CLEARED"},
        {"2025-09-15", "-96.40", "acc-0001", "cat-0005", "CLEARED"},
        {"2025-09-19", "-127.80", "acc-0001", "cat-0006", "CLEARED"},
        {"2025-09-26", "-54.20", "acc-0003", "cat-0008", "CLEARED"},
        // October
        {"2025-10-01", "3050.00", "acc-0001", "cat-0002", "CLEARED"},
        {"2025-10-01", "-1400.00", "acc-0001", "cat-0004", "CLEARED"},
        {"2025-10-03", "-104.60", "acc-0001", "cat-0006", "CLEARED"},
        {"2025-10-05", "-62.00", "acc-0003", "cat-0007", "CLEARED"},
        {"2025-10-11", "-46.75", "acc-0003", "cat-0008", "CLEARED"},
        {"2025-10-15", "-112.80", "acc-0001", "cat-0005", "CLEARED"},
        {"2025-10-17", "-131.15", "acc-0001", "cat-0006", "CLEARED"},
        {"2025-10-24", "-29.90", "acc-0003", "cat-0008", "CLEARED"},
        // November
        {"2025-11-01", "3050.00", "acc-0001", "cat-0002", "CLEARED"},
        {"2025-11-01", "-1400.00", "acc-0001", "cat-0004", "CLEARED"},
        {"2025-11-04", "-118.25", "acc-0001", "cat-0006", "CLEARED"},
        {"2025-11-05", "-62.00", "acc-0003", "cat-0007", "CLEARED"},
        {"2025-11-08", "-61.30", "acc-0003", "cat-0008", "CLEARED"},
        {"2025-11-15", "-141.25", "acc-0001", "cat-0005", "CLEARED"},
        {"2025-11-20", "-142.60", "acc-0001", "cat-0006", "CLEARED"},
        {"2025-11-28", "-35.40", "acc-0003", "cat-0008", "CLEARED"},
        // December: the bonus
        {"2025-12-01", "3650.00", "acc-0001", "cat-0002", "CLEARED"},
        {"2025-12-01", "-1400.00", "acc-0001", "cat-0004", "CLEARED"},
        {"2025-12-04", "-134.90", "acc-0001", "cat-0006", "CLEARED"},
        {"2025-12-05", "-62.00", "acc-0003", "cat-0007", "CLEARED"},
        {"2025-12-13", "-88.60", "acc-0003", "cat-0008", "CLEARED"},
        {"2025-12-15", "-168.90", "acc-0001", "cat-0005", "CLEARED"},
        {"2025-12-19", "-186.45", "acc-0001", "cat-0006", "CLEARED"},
        {"2025-12-22", "-74.10", "acc-0003", "cat-0008", "CLEARED"},
        {"2025-12-23", "-45.00", "acc-0003", "cat-0007", "CLEARED"},
        // January 2026: a duplicate transit pass, refunded
        {"2026-01-01", "3050.00", "acc-0001", "cat-0002", "CLEARED"},
        {"2026-01-01", "-1400.00", "acc-0001", "cat-0004", "CLEARED"},
        {"2026-01-05", "-62.00", "acc-0003", "cat-0007", "CLEARED"},
        {"2026-01-06", "-97.30", "acc-0001", "cat-0006", "CLEARED"},
        {"2026-01-07", "-62.00", "acc-0003", "cat-0007", "CLEARED"},
        {"2026-01-09", "62.00", "acc-0003", "cat-0007", "CLEARED"},
        {"2026-01-15", "-176.35", "acc-0001", "cat-0005", "CLEARED"},
        {"2026-01-21", "-121.70", "acc-0001", "cat-0006", "CLEARED"},
        {"2026-01-30", "-32.80", "acc-0003", "cat-0008", "CLEARED"},
        // February
        {"2026-02-01", "3050.00", "acc-0001", "cat-0002", "CLEARED"},
        {"2026-02-01", "-1400.00", "acc-0001", "cat-0004", "CLEARED"},
        {"2026-02-03", "-109.85", "acc-0001", "cat-0006", "CLEARED"},
        {"2026-02-05", "-62.00", "acc-0003", "cat-0007", "CLEARED"},
        {"2026-02-14", "-96.40", "acc-0003", "cat-0008", "CLEARED"},
        {"2026-02-15", "-159.10", "acc-0001", "cat-0005", "CLEARED"},
        {"2026-02-18", "-118.30", "acc-0001", "cat-0006", "CLEARED"},
        // March: a grocery return
        {"2026-03-01", "3050.00", "acc-0001", "cat-0002", "CLEARED"},
        {"2026-03-01", "-1400.00", "acc-0001", "cat-0004", "CLEARED"},
        {"2026-03-04", "-126.45", "acc-0001", "cat-0006", "CLEARED"},
        {"2026-03-05", "-62.00", "acc-0003", "cat-0007", "CLEARED"},
        {"2026-03-07", "18.40", "acc-0001", "cat-0006", "CLEARED"},
        {"2026-03-13", "-41.60", "acc-0003", "cat-0008", "CLEARED"},
        {"2026-03-15", "-132.60", "acc-0001", "cat-0005", "CLEARED"},
        {"2026-03-20", "-114.95", "acc-0001", "cat-0006", "CLEARED"},
        {"2026-03-27", "-58.25", "acc-0003", "cat-0008", "CLEARED"},
        // April: the raise, and the new rent
        {"2026-04-01", "3200.00", "acc-0001", "cat-0002", "CLEARED"},
        {"2026-04-01", "-1450.00", "acc-0001", "cat-0004", "CLEARED"},
        {"2026-04-03", "-101.20", "acc-0001", "cat-0006", "CLEARED"},
        {"2026-04-05", "-62.00", "acc-0003", "cat-0007", "CLEARED"},
        {"2026-04-10", "-44.30", "acc-0003", "cat-0008", "CLEARED"},
        {"2026-04-15", "-108.45", "acc-0001", "cat-0005", "CLEARED"},
        {"2026-04-17", "-129.60", "acc-0001", "cat-0006", "CLEARED"},
        // May: the wedding
        {"2026-05-01", "3200.00", "acc-0001", "cat-0002", "CLEARED"},
        {"2026-05-01", "-1450.00", "acc-0001", "cat-0004", "CLEARED"},
        {"2026-05-05", "-115.40", "acc-0001", "cat-0006", "CLEARED"},
        {"2026-05-05", "-62.00", "acc-0003", "cat-0007", "CLEARED"},
        {"2026-05-09", "-1200.00", "acc-0001", "cat-0009", "CLEARED"},
        {"2026-05-15", "-94.20", "acc-0001", "cat-0005", "CLEARED"},
        {"2026-05-16", "-67.85", "acc-0003", "cat-0008", "CLEARED"},
        {"2026-05-22", "-133.10", "acc-0001", "cat-0006", "CLEARED"},
        // June
        {"2026-06-01", "3200.00", "acc-0001", "cat-0002", "CLEARED"},
        {"2026-06-01", "-1450.00", "acc-0001", "cat-0004", "CLEARED"},
        {"2026-06-04", "-108.75", "acc-0001", "cat-0006", "CLEARED"},
        {"2026-06-05", "-62.00", "acc-0003", "cat-0007", "CLEARED"},
        {"2026-06-06", "-350.00", "acc-0001", "cat-0009", "CLEARED"},
        {"2026-06-12", "-52.40", "acc-0003", "cat-0008", "CLEARED"},
        {"2026-06-15", "-88.75", "acc-0001", "cat-0005", "CLEARED"},
        {"2026-06-19", "-119.35", "acc-0001", "cat-0006", "CLEARED"},
        {"2026-06-26", "-39.95", "acc-0003", "cat-0008", "CLEARED"},
        // July
        {"2026-07-01", "3200.00", "acc-0001", "cat-0002", "CLEARED"},
        {"2026-07-01", "-1450.00", "acc-0001", "cat-0004", "CLEARED"},
        {"2026-07-03", "-98.60", "acc-0001", "cat-0006", "CLEARED"},
        {"2026-07-05", "-62.00", "acc-0003", "cat-0007", "CLEARED"},
        {"2026-07-11", "-71.20", "acc-0003", "cat-0008", "CLEARED"},
        {"2026-07-15", "-101.30", "acc-0001", "cat-0005", "CLEARED"},
        {"2026-07-17", "-126.85", "acc-0001", "cat-0006", "CLEARED"},
        {"2026-07-24", "-33.15", "acc-0003", "cat-0008", "CLEARED"},
        // August 2026: DEMO_TRANSACTIONS, row for row (the transfer is below)
        {"2026-08-01", "3200.00", "acc-0001", "cat-0002", "CLEARED"},
        {"2026-08-01", "-1450.00", "acc-0001", "cat-0004", "CLEARED"},
        {"2026-08-03", "-86.40", "acc-0001", "cat-0006", "CLEARED"},
        {"2026-08-05", "-62.00", "acc-0003", "cat-0007", "CLEARED"},
        {"2026-08-09", "-41.20", "acc-0003", "cat-0008", "CLEARED"},
        {"2026-08-12", "-104.75", "acc-0001", "cat-0006", "CLEARED"},
        {"2026-08-15", "-128.30", "acc-0001", "cat-0005", "CLEARED"},
        {"2026-08-20", "-92.15", "acc-0001", null, "PENDING"},
    };

    /** Transfers, both legs: {@code date, amount, from, to}. August's is DEMO_TRANSFER. */
    static final String[][] DEMO_TRANSFERS = {
        {"2025-10-16", "300.00", "acc-0001", "acc-0002"},
        {"2026-01-16", "300.00", "acc-0001", "acc-0002"},
        {"2026-04-16", "300.00", "acc-0001", "acc-0002"},
        {"2026-07-16", "300.00", "acc-0001", "acc-0002"},
        {"2026-08-16", "500.00", "acc-0001", "acc-0002"},
    };

    /**
     * Generates both report fixtures — every range the chips ask for, every month "where it went"
     * can step to, and each as CSV — from the demo household's ledger, through the real endpoints,
     * with the clock at the demo instant.
     */
    @Test
    void the_committed_report_fixtures_are_what_the_application_returns() throws IOException {
        clock.moveTo(Instant.now());
        Household household = seedDemoHousehold();
        Map<UUID, String> stable = seedTheDemoLedger(household);
        clock.moveTo(DEMO_TODAY);

        Map<String, String> generated = new LinkedHashMap<>();
        readReports(household.owner(), "", REPORT_RANGES, demoMonths(), generated);

        String document =
                canonicalise(renderReports(generated), new Fixture(Map.of(), null, stable));
        compareOrWrite(document, COMMITTED_REPORTS, REGENERATED_REPORTS);
    }

    /**
     * The two Insights states the demo household cannot have, from a household of their own so the
     * demo figures cannot be perturbed: nothing recorded at all, and money in two currencies.
     */
    @Test
    void the_committed_report_state_fixtures_are_what_the_application_returns() throws IOException {
        clock.moveTo(Instant.now());
        Household household = seedDemoHousehold();
        clock.moveTo(DEMO_TODAY);

        Map<String, String> generated = new LinkedHashMap<>();
        readReports(
                household.owner(),
                "EMPTY ",
                List.of(REPORT_RANGES.get(0)),
                List.of("2026-07"),
                generated);

        clock.moveTo(Instant.now());
        Map<UUID, String> stable = new LinkedHashMap<>();
        seedTwoCurrencies(household, stable);
        clock.moveTo(DEMO_TODAY);
        readReports(
                household.owner(),
                "TWO_CURRENCIES ",
                List.of(REPORT_RANGES.get(0)),
                List.of("2026-07"),
                generated);

        compareOrWrite(
                canonicalise(
                        render(generated, "REPORT_STATES"), new Fixture(Map.of(), null, stable)),
                COMMITTED_REPORT_STATES,
                REGENERATED_REPORT_STATES);
    }

    /** Every month "Where {month} went" can show without leaving the demo's year. */
    private static List<String> demoMonths() {
        List<String> months = new java.util.ArrayList<>();
        for (java.time.YearMonth m = java.time.YearMonth.of(2025, 9);
                !m.isAfter(java.time.YearMonth.of(2026, 8));
                m = m.plusMonths(1)) {
            months.add(m.toString());
        }
        return months;
    }

    private static void readReports(
            ApiClient client,
            String prefix,
            List<String> ranges,
            List<String> months,
            Map<String, String> into) {
        for (String range : ranges) {
            String path = "/api/reports/income-and-spending?" + range;
            into.put(prefix + "GET " + path, ok(client.get(path)).body());
            into.put(
                    prefix + "GET " + path + " CSV",
                    jsonString(ok(client.getAccepting(path, "text/csv")).body()));
        }
        for (String month : months) {
            String path = "/api/reports/spending-by-category?from=" + month + "&to=" + month;
            into.put(prefix + "GET " + path, ok(client.get(path)).body());
            into.put(
                    prefix + "GET " + path + " CSV",
                    jsonString(ok(client.getAccepting(path, "text/csv")).body()));
        }
    }

    private static ApiResponse ok(ApiResponse response) {
        if (response.status() != 200) {
            throw new AssertionError("a report read failed: " + response.body());
        }
        return response;
    }

    /**
     * The demo household's categories, accounts and ledger, entered through the API.
     *
     * @return the generated ids, by the stable names {@code demo-data.ts} uses
     */
    private Map<UUID, String> seedTheDemoLedger(Household household) {
        ApiClient owner = household.owner();
        Map<String, UUID> categories = seedDemoCategories(owner);
        Map<String, UUID> accounts = seedDemoAccounts(owner);

        for (String[] row : DEMO_LEDGER) {
            ApiResponse created =
                    owner.post(
                            "/api/transactions",
                            """
                            {"accountId":"%s","date":"%s","amount":"%s","categoryId":%s,
                             "status":"%s"}
                            """
                                    .formatted(
                                            accounts.get(row[2]),
                                            row[0],
                                            row[1],
                                            row[3] == null
                                                    ? "null"
                                                    : "\"" + categories.get(row[3]) + "\"",
                                            row[4]));
            if (created.status() != 201) {
                throw new AssertionError("could not seed a demo transaction: " + created.body());
            }
        }
        for (String[] transfer : DEMO_TRANSFERS) {
            transfer(
                    owner,
                    accounts.get(transfer[2]),
                    accounts.get(transfer[3]),
                    transfer[0],
                    transfer[1]);
        }
        // Archived after its history was written, as a household would: the wedding is over.
        ok200(owner.post("/api/categories/" + categories.get("cat-0009") + "/archive", null));

        Map<UUID, String> stable = new LinkedHashMap<>();
        categories.forEach((name, id) -> stable.put(id, name));
        accounts.forEach((name, id) -> stable.put(id, name));
        return stable;
    }

    /** The demo household's categories, by the stable names {@code demo-data.ts} uses. */
    private Map<String, UUID> seedDemoCategories(ApiClient owner) {
        Map<String, UUID> categories = new LinkedHashMap<>();
        categories.put("cat-0001", seededCategoryId("Income"));
        categories.put("cat-0002", seededCategoryId("Salary"));
        categories.put("cat-0003", seededCategoryId("Bills"));
        categories.put("cat-0004", seededCategoryId("Rent or mortgage"));
        categories.put("cat-0005", seededCategoryId("Utilities"));
        categories.put("cat-0006", seededCategoryId("Groceries"));
        categories.put("cat-0007", seededCategoryId("Transport"));
        categories.put("cat-0008", seededCategoryId("Eating Out"));
        // demo-data.ts calls it "Rent".
        ok200(
                owner.patch(
                        "/api/categories/" + categories.get("cat-0004"),
                        "{\"version\":0,\"name\":\"Rent\"}"));
        categories.put("cat-0009", createCategory(owner, "Wedding", "EXPENSE", null));
        return categories;
    }

    /** The demo household's three accounts, by the stable names {@code demo-data.ts} uses. */
    private Map<String, UUID> seedDemoAccounts(ApiClient owner) {
        Map<String, UUID> accounts = new LinkedHashMap<>();
        accounts.put(
                "acc-0001", demoAccount(owner, "Everyday Checking", "4417", "CHECKING", "1641.75"));
        accounts.put("acc-0002", demoAccount(owner, "Emergency Fund", null, "SAVINGS", "5620.00"));
        accounts.put(
                "acc-0003", demoAccount(owner, "Travel Card", "8820", "CREDIT_CARD", "-215.24"));
        return accounts;
    }

    // =========================================================== mobile fixtures (slice 15)

    /**
     * Where the Flutter app's fake HTTP layer reads from (mobile/test/support/fixture_server.dart).
     *
     * <p>The mobile equivalent of the {@code *.generated.ts} files above, for the same reason: a
     * golden screenshot shows whatever the fixture says, and a hand-written balance would be a
     * figure the application never computed. The fake answers <b>only</b> the exchanges written
     * here and fails the test on anything else, so a screen cannot render a response nobody
     * generated.
     */
    private static final Path MOBILE_FIXTURES = Path.of("..", "mobile", "test", "fixtures");

    /** The device label the app derives on an Android phone (mobile/lib/core/platform). */
    private static final String PHONE_LABEL = "Phone · Android";

    /** The ledger as the app asks for it: first page, the web's page size. */
    private static final String LEDGER = "/api/transactions?page=0&size=50";

    private static final String ACCOUNTS = "/api/accounts?includeArchived=true&page=0&size=200";

    private static final String CATEGORIES = "/api/categories?includeArchived=true&page=0&size=200";

    /**
     * A payee for each kind of row, so the phone's ledger reads like a ledger. The figures are
     * {@link #DEMO_LEDGER}'s own — the mobile household is its July and August, not a third copy of
     * anybody's arithmetic.
     */
    private static final Map<String, String> MOBILE_PAYEES =
            Map.of(
                    "cat-0002", "Northwind Systems",
                    "cat-0004", "Fairview Lettings",
                    "cat-0005", "Metro Power",
                    "cat-0006", "Greenfield Market",
                    "cat-0007", "City Transit",
                    "cat-0008", "Corner Kitchen",
                    "none", "Greenfield Market");

    /** The ids a mobile scenario needs to address, by a name a test can read. */
    private record MobileDemo(
            Map<String, UUID> accounts,
            Map<String, UUID> categories,
            Map<String, UUID> rows,
            UUID augustTransfer,
            Map<UUID, String> stable) {

        String account(String name) {
            return accounts.get(name).toString();
        }

        String category(String name) {
            return categories.get(name).toString();
        }

        String row(String name) {
            return rows.get(name).toString();
        }
    }

    /** Who made a recorded request: a token's owner by name, or nobody. */
    private record Caller(String name, ApiClient client, Map<String, String> headers) {

        static Caller stranger(ApiClient client) {
            return new Caller(null, client, Map.of());
        }
    }

    /**
     * Records exchanges as the mobile fake replays them.
     *
     * <p>Each exchange carries the <b>state</b> it belongs to (the fake starts in one and a write
     * moves it to the exchange's {@code then}), the request exactly as the app must send it — the
     * bearer's owner, any CSRF header, the JSON body — and the response exactly as the API gave it.
     * {@code "*"} is any state, for the public reads an app makes whenever it likes.
     */
    private static final class MobileRecorder {

        private static final ObjectMapper JSON = JsonMapper.builder().build();

        private final ArrayNode exchanges = JSON.createArrayNode();
        private final Map<String, String> secrets = new LinkedHashMap<>();
        private String state = "*";

        void in(String next) {
            state = next;
        }

        /** A credential that must not reach the file: replaced by {@code canonical} everywhere. */
        void secret(String raw, String canonical) {
            secrets.put(raw, canonical);
        }

        ApiResponse send(Caller caller, String method, String path, String body) {
            return exchange(caller, method, path, body, null);
        }

        ApiResponse sendThen(String then, Caller caller, String method, String path, String body) {
            return exchange(caller, method, path, body, then);
        }

        private ApiResponse exchange(
                Caller caller, String method, String path, String body, String then) {
            ApiResponse response = caller.client().send(method, path, body);
            ObjectNode exchange = JSON.createObjectNode();
            exchange.put("state", state);
            exchange.put("method", method);
            exchange.put("path", path);
            if (caller.name() == null) {
                exchange.putNull("auth");
            } else {
                exchange.put("auth", caller.name());
            }
            if (!caller.headers().isEmpty()) {
                ObjectNode headers = exchange.putObject("requestHeaders");
                new TreeMap<>(caller.headers()).forEach(headers::put);
            }
            exchange.set("request", body == null ? JSON.nullNode() : JSON.readTree(body));
            exchange.put("status", response.status());
            // Only the headers the app reads: Retry-After on a 429, the meta route's caching, and
            // the CSRF cookie from the one read the app takes it from before a public write.
            ObjectNode headers = JSON.createObjectNode();
            response.header("Retry-After").ifPresent(value -> headers.put("Retry-After", value));
            if (path.equals("/api/meta")) {
                response.header("Cache-Control")
                        .ifPresent(value -> headers.put("Cache-Control", value));
            }
            if (path.equals("/api/setup/status")) {
                response.headerValues("Set-Cookie").stream()
                        .filter(cookie -> cookie.startsWith("XSRF-TOKEN="))
                        .findFirst()
                        .ifPresent(cookie -> headers.put("Set-Cookie", cookie));
            }
            if (!headers.isEmpty()) {
                exchange.set("responseHeaders", headers);
            }
            exchange.set(
                    "body",
                    response.body() == null || response.body().isBlank()
                            ? JSON.nullNode()
                            : JSON.readTree(response.body()));
            if (then != null) {
                exchange.put("then", then);
            }
            exchanges.add(exchange);
            return response;
        }

        String render(String scenario) {
            ObjectNode root = JSON.createObjectNode();
            root.put(
                    "_generated",
                    "GENERATED by backend DemoFixtureIT ("
                            + scenario
                            + "). Do not edit by hand. Real responses of the real application,"
                            + " clock fixed, ids and credentials canonicalised. Regenerate: mvn -f"
                            + " backend test -Dtest=DemoFixtureIT -Dfixtures.write=true");
            root.set("exchanges", exchanges);
            String document = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            for (Map.Entry<String, String> secret : secrets.entrySet()) {
                document = document.replace(secret.getKey(), secret.getValue());
            }
            return document + "\n";
        }
    }

    /**
     * The phone's demo household, and every exchange the built screens make against it.
     *
     * <p>One story, told in order, because each write moves the server: connect, sign in, read the
     * ledger, record a coffee, delete it, file the pending shop, move money, correct the transfer,
     * delete it, move money into euros, split August's transfer, pair it back. Each step's reads
     * are recorded in the state it leaves the server in, so a test that starts in any state sees
     * exactly the ledger the API would have shown it.
     */
    @Test
    void the_committed_mobile_fixtures_are_what_the_application_returns() throws IOException {
        clock.moveTo(Instant.now());
        Household household = seedDemoHousehold();
        MobileDemo demo = seedTheMobileLedger(household);
        // The clock stays at real time: nothing these screens read is derived from today, and a
        // token issued "in August 2026" would expire before the database says it was created
        // (ck_auth_tokens_expires_after_creation). Every timestamp is pinned when rendered.

        MobileRecorder record = new MobileRecorder();
        Caller stranger = Caller.stranger(anonymous());

        // Any state: an app asks who the server is whenever it starts.
        record.in("*");
        record.send(stranger, "GET", "/api/meta", null);
        record.send(stranger, "GET", "/api/setup/status", null);

        // Connecting and signing in.
        record.in("connect");
        record.send(stranger, "POST", "/api/auth/token", tokenBody(OWNER_EMAIL, "wrong-guess-1"));
        Caller owner = signInAsPhone(record, stranger, "demo", OWNER_EMAIL, OWNER_SECRET, "owner");
        Caller viewer =
                signInAsPhone(record, stranger, "viewer", VIEWER_EMAIL, VIEWER_SECRET, "viewer");

        // ---- demo: the household as the owner first sees it.
        record.in("demo");
        readTheLedger(record, owner, demo);
        for (String filter :
                List.of(
                        "&accountId=" + demo.account("acc-0003"),
                        "&uncategorised=true",
                        "&categoryId=" + demo.category("cat-0006"),
                        "&from=2026-08-01&to=2026-08-31",
                        "&search=market")) {
            record.send(owner, "GET", LEDGER + filter, null);
        }
        for (String typed : List.of("Co", "Gr", "Corner%20Kitchen")) {
            record.send(owner, "GET", "/api/transactions/payee-suggestions?typed=" + typed, null);
        }
        record.send(owner, "GET", "/api/transfers/" + demo.augustTransfer(), null);
        // Two rows going the same way cannot be one transfer — a real 422, rendered from its code.
        record.send(
                owner,
                "POST",
                "/api/transactions/" + demo.row("groceries-aug-03") + "/pair",
                """
                {"withTransactionId":"%s","version":0,"withVersion":0}
                """
                        .formatted(demo.row("dinner-aug-09")));

        // ---- viewer: the same household, read-only.
        record.in("viewer");
        readTheLedger(record, viewer, demo);
        record.send(viewer, "POST", "/api/transactions", coffee(demo));

        // ---- record a coffee, then delete it.
        record.in("demo");
        ApiResponse coffee =
                record.sendThen("added", owner, "POST", "/api/transactions", coffee(demo));
        nameRows(demo.stable());
        record.in("added");
        readTheLedger(record, owner, demo);
        record.sendThen(
                "deleted", owner, "DELETE", "/api/transactions/" + coffee.stringField("id"), null);
        record.in("deleted");
        readTheLedger(record, owner, demo);

        // ---- file the pending shop under Groceries.
        record.sendThen(
                "categorised",
                owner,
                "PATCH",
                "/api/transactions/" + demo.row("pending-groceries"),
                """
                {"version":0,"categoryId":"%s"}
                """
                        .formatted(demo.category("cat-0006")));
        record.in("categorised");
        readTheLedger(record, owner, demo);

        // A phone still holding version 0 of that row: somebody else's edit got there first.
        record.in("demo");
        record.send(
                owner,
                "PATCH",
                "/api/transactions/" + demo.row("pending-groceries"),
                """
                {"version":0,"note":"weekly shop"}
                """);

        // ---- move money, correct it, delete it.
        record.in("categorised");
        ApiResponse moved =
                record.sendThen(
                        "moved",
                        owner,
                        "POST",
                        "/api/transfers",
                        """
                        {"fromAccountId":"%s","toAccountId":"%s","date":"2026-08-20",
                         "amount":"200.00","note":"Top up"}
                        """
                                .formatted(demo.account("acc-0001"), demo.account("acc-0002")));
        String movedId = moved.stringField("id");
        demo.stable().put(UUID.fromString(movedId), "tgr-0002");
        nameRows(demo.stable());
        record.in("moved");
        readTheLedger(record, owner, demo);
        ApiResponse readBack = record.send(owner, "GET", "/api/transfers/" + movedId, null);
        record.sendThen(
                "transfer-edited",
                owner,
                "PATCH",
                "/api/transfers/" + movedId,
                """
                {"version":%d,"amount":"250.00"}
                """
                        .formatted(readBack.numberField("version")));
        record.in("transfer-edited");
        readTheLedger(record, owner, demo);
        record.sendThen("transfer-deleted", owner, "DELETE", "/api/transfers/" + movedId, null);
        record.in("transfer-deleted");
        readTheLedger(record, owner, demo);

        // ---- into euros: two figures, because only the statement knows the second (ADR-0033).
        ApiResponse crossed =
                record.sendThen(
                        "crossed",
                        owner,
                        "POST",
                        "/api/transfers",
                        """
                        {"fromAccountId":"%s","toAccountId":"%s","date":"2026-08-20",
                         "amount":"100.00","amountReceived":"92.50"}
                        """
                                .formatted(demo.account("acc-0001"), demo.account("acc-0004")));
        demo.stable().put(UUID.fromString(crossed.stringField("id")), "tgr-0003");
        nameRows(demo.stable());
        record.in("crossed");
        readTheLedger(record, owner, demo);
        record.send(owner, "GET", "/api/transfers/" + crossed.stringField("id"), null);

        // ---- split August's transfer into two rows, then pair them back.
        ApiResponse august =
                record.send(owner, "GET", "/api/transfers/" + demo.augustTransfer(), null);
        ApiResponse split =
                record.sendThen(
                        "split",
                        owner,
                        "POST",
                        "/api/transfers/" + demo.augustTransfer() + "/split",
                        """
                        {"version":%d}
                        """
                                .formatted(august.numberField("version")));
        record.in("split");
        readTheLedger(record, owner, demo);
        JsonNode legs = MobileRecorder.JSON.readTree(split.body()).get("transactions");
        JsonNode out =
                legs.get(0).get("amount").asString().startsWith("-") ? legs.get(0) : legs.get(1);
        JsonNode in = out == legs.get(0) ? legs.get(1) : legs.get(0);
        ApiResponse paired =
                record.sendThen(
                        "paired",
                        owner,
                        "POST",
                        "/api/transactions/" + out.get("id").asString() + "/pair",
                        """
                {"withTransactionId":"%s","version":%d,"withVersion":%d}
                """
                                .formatted(
                                        in.get("id").asString(),
                                        out.get("version").asLong(),
                                        in.get("version").asLong()));
        demo.stable().put(UUID.fromString(paired.stringField("id")), "tgr-0004");
        record.in("paired");
        readTheLedger(record, owner, demo);

        // ---- a member removed from the household signs in to the not-a-member screen.
        ApiClient ownerBrowser = signIn(OWNER_EMAIL, OWNER_SECRET);
        ok204(
                ownerBrowser.delete(
                        "/api/households/current/members/" + membershipIdOf(MEMBER_EMAIL)));
        record.in("connect");
        Caller removed =
                signInAsPhone(
                        record, stranger, "no-household", MEMBER_EMAIL, MEMBER_SECRET, "member");
        record.in("no-household");
        record.send(removed, "GET", "/api/auth/me", null);
        // Their way out is signing out, which ends their token like anybody else's.
        record.sendThen("connect", removed, "POST", "/api/auth/logout", null);

        // ---- devices: the phone sees itself, and signs a browser out.
        record.in("demo");
        ApiResponse devices = record.send(owner, "GET", "/api/auth/devices?page=0&size=50", null);
        java.util.regex.Matcher session =
                java.util.regex.Pattern.compile("\"(s-[0-9a-f]{64})\"").matcher(devices.body());
        int sessions = 0;
        while (session.find()) {
            record.secret(session.group(1), String.format("s-session-%04d", ++sessions));
        }
        session.reset();
        if (!session.find()) {
            throw new AssertionError("the owner's browser session is not on the devices list");
        }
        record.sendThen(
                "device-revoked", owner, "DELETE", "/api/auth/devices/" + session.group(1), null);
        record.in("device-revoked");
        record.send(owner, "GET", "/api/auth/devices?page=0&size=50", null);

        // ---- signing out ends the token; every read after it is the 401 a revoked phone gets.
        record.in("*");
        record.sendThen("revoked", owner, "POST", "/api/auth/logout", null);
        record.in("revoked");
        readTheLedger(record, owner, demo);

        compareOrWriteMobile(
                canonicaliseMobile(record.render("demo household"), demo.stable()),
                MOBILE_FIXTURES.resolve("demo.generated.json"),
                Path.of("target", "mobile-demo.generated.json"));
    }

    /**
     * Names every transaction and payee the database holds that has no name yet, by what it is.
     *
     * <p>Needed because the ledger orders rows {@code date desc, id desc} and ids are random: two
     * rows on one date (a salary and the rent, both legs of a transfer) come back in a different
     * order each run, and naming ids by first appearance would then renumber everything after them.
     * A row named by its date, account and amount keeps its name however it sorts. Called after
     * every write that makes rows, while they exist — a deleted coffee must already have its name.
     */
    private void nameRows(Map<UUID, String> stable) {
        java.util.Set<String> used = new java.util.HashSet<>(stable.values());
        for (Map<String, Object> row :
                jdbc.queryForList(
                        "SELECT id, date, account_id, amount FROM transactions ORDER BY date, id")) {
            UUID id = (UUID) row.get("id");
            if (stable.containsKey(id)) {
                continue;
            }
            java.math.BigDecimal amount = (java.math.BigDecimal) row.get("amount");
            String base =
                    "row-"
                            + row.get("date")
                            + "-"
                            + stable.getOrDefault((UUID) row.get("account_id"), "acc-other")
                            + (amount.signum() < 0 ? "-out-" : "-in-")
                            + amount.abs().stripTrailingZeros().toPlainString();
            String name = base;
            for (int n = 2; used.contains(name); n++) {
                name = base + "-" + n;
            }
            used.add(name);
            stable.put(id, name);
        }
        for (Map<String, Object> payee : jdbc.queryForList("SELECT id, name FROM payees")) {
            UUID id = (UUID) payee.get("id");
            stable.putIfAbsent(
                    id,
                    "payee-"
                            + ((String) payee.get("name"))
                                    .toLowerCase(java.util.Locale.ROOT)
                                    .replaceAll("[^a-z0-9]+", "-"));
        }
    }

    /**
     * {@link #compareOrWrite}, insensitive to the order of rows that share a date.
     *
     * <p>The ledger's order among same-date rows is the order of their random ids, so it is real
     * and arbitrary: a regeneration may flip a salary and the rent. The committed file keeps
     * whichever real order it was written with; the comparison sorts each page's rows by date and
     * stable id first, so a flip is not drift, while any other change — a figure, a field, a row —
     * still fails. Writing works the same way: a regeneration that differs only by a flip leaves
     * the committed file alone, so the goldens drawn from it do not churn.
     */
    private static void compareOrWriteMobile(String document, Path committed, Path regenerated)
            throws IOException {
        if (Files.exists(committed)) {
            JsonNode now = tieOrderFree(MobileRecorder.JSON.readTree(document));
            JsonNode then = tieOrderFree(MobileRecorder.JSON.readTree(Files.readString(committed)));
            if (now.equals(then)) {
                return;
            }
        }
        compareOrWrite(document, committed, regenerated);
    }

    private static JsonNode tieOrderFree(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = MobileRecorder.JSON.createObjectNode();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                copy.set(field.getKey(), tieOrderFree(field.getValue()));
            }
            return copy;
        }
        if (node.isArray()) {
            List<JsonNode> items = new java.util.ArrayList<>();
            node.forEach(item -> items.add(tieOrderFree(item)));
            if (items.stream().allMatch(item -> item.has("date") && item.has("id"))) {
                items.sort(
                        java.util.Comparator.comparing(
                                        (JsonNode item) -> item.get("date").asString())
                                .reversed()
                                .thenComparing(item -> item.get("id").asString()));
            }
            ArrayNode copy = MobileRecorder.JSON.createArrayNode();
            items.forEach(copy::add);
            return copy;
        }
        return node;
    }

    /**
     * A fresh instance, as a phone meets it: set up the first account, sign in to an empty
     * household, then accept an invitation as a second person.
     */
    @Test
    void the_committed_mobile_first_run_fixtures_are_what_the_application_returns()
            throws IOException {
        clock.moveTo(Instant.now());
        MobileRecorder record = new MobileRecorder();
        Caller stranger = Caller.stranger(anonymous());

        record.in("*");
        record.send(stranger, "GET", "/api/meta", null);

        // ---- fresh: the CSRF cookie comes from the status read, and the public write sends it
        // back as a header (SecurityConfig: a cookie-less client still needs it on public writes).
        // A client of its own, holding no cookie yet, as the app never does.
        record.in("fresh");
        ApiClient phone = anonymous();
        record.send(Caller.stranger(phone), "GET", "/api/setup/status", null);
        String csrf = phone.cookie("XSRF-TOKEN").orElseThrow();
        record.secret(csrf, "fixture-csrf");
        Caller withCsrf =
                new Caller(
                        null, phone, Map.of("X-XSRF-TOKEN", csrf, "Cookie", "XSRF-TOKEN=" + csrf));
        record.sendThen(
                "set-up",
                withCsrf,
                "POST",
                "/api/setup/first-user",
                """
                {"displayName":"Ada","email":"%s","password":"%s",
                 "householdName":"Rivera Household","baseCurrency":"USD"}
                """
                        .formatted(OWNER_EMAIL, OWNER_SECRET));

        record.in("set-up");
        record.send(stranger, "GET", "/api/setup/status", null);
        Caller owner = signInAsPhone(record, stranger, "empty", OWNER_EMAIL, OWNER_SECRET, "owner");

        record.in("empty");
        record.send(owner, "GET", "/api/auth/me", null);
        record.send(owner, "GET", ACCOUNTS, null);
        record.send(owner, "GET", CATEGORIES, null);
        record.send(owner, "GET", LEDGER, null);
        Map<UUID, String> stable = new LinkedHashMap<>();
        stable.put(seededCategoryId("Groceries"), "cat-0006");

        // ---- an invitation, made on the web and pasted into a second phone.
        clock.moveTo(Instant.now());
        String invitation = invite(signIn(OWNER_EMAIL, OWNER_SECRET), MEMBER_EMAIL, "MEMBER");
        record.secret(invitation, "fixture-invitation");
        ApiClient secondPhone = anonymous();
        record.in("invited");
        record.send(Caller.stranger(secondPhone), "GET", "/api/setup/status", null);
        String secondCsrf = secondPhone.cookie("XSRF-TOKEN").orElseThrow();
        record.secret(secondCsrf, "fixture-csrf-2");
        Caller joining =
                new Caller(
                        null,
                        secondPhone,
                        Map.of("X-XSRF-TOKEN", secondCsrf, "Cookie", "XSRF-TOKEN=" + secondCsrf));
        record.send(
                joining,
                "POST",
                "/api/invitations/not-an-invitation/accept",
                """
                {"displayName":"Grace","password":"%s"}
                """
                        .formatted(MEMBER_SECRET));
        record.sendThen(
                "joined",
                joining,
                "POST",
                "/api/invitations/" + invitation + "/accept",
                """
                {"displayName":"Grace","password":"%s"}
                """
                        .formatted(MEMBER_SECRET));
        record.in("joined");
        // The sign-in screen asks whether password sign-in is on before it is shown.
        record.send(Caller.stranger(secondPhone), "GET", "/api/setup/status", null);
        Caller member =
                signInAsPhone(
                        record,
                        Caller.stranger(anonymous()),
                        "member",
                        MEMBER_EMAIL,
                        MEMBER_SECRET,
                        "member");
        record.in("member");
        record.send(member, "GET", "/api/auth/me", null);
        record.send(member, "GET", ACCOUNTS, null);
        record.send(member, "GET", CATEGORIES, null);
        record.send(member, "GET", LEDGER, null);

        compareOrWrite(
                canonicaliseMobile(record.render("first run"), stable),
                MOBILE_FIXTURES.resolve("first-run.generated.json"),
                Path.of("target", "mobile-first-run.generated.json"));
    }

    /** Issues a phone's token through a recorded exchange, and names it in the file. */
    private Caller signInAsPhone(
            MobileRecorder record,
            Caller stranger,
            String then,
            String email,
            String secret,
            String who) {
        ApiResponse issued =
                record.sendThen(
                        then, stranger, "POST", "/api/auth/token", tokenBody(email, secret));
        if (issued.status() != 201) {
            throw new AssertionError("could not issue a phone token: " + issued.body());
        }
        String token = issued.stringField("token");
        record.secret(token, "fixture-token-" + who);
        return new Caller(who, anonymous().withBearerToken(token), Map.of());
    }

    private static String tokenBody(String email, String secret) {
        return """
               {"email":"%s","password":"%s","deviceLabel":"%s"}
               """
                .formatted(email, secret, PHONE_LABEL);
    }

    /** The coffee the journey records, exactly as the add sheet sends it. */
    private static String coffee(MobileDemo demo) {
        return """
               {"accountId":"%s","date":"2026-08-20","amount":"-4.50","status":"CLEARED",
                "payeeName":"Corner Kitchen","categoryId":"%s"}
               """
                .formatted(demo.account("acc-0001"), demo.category("cat-0008"));
    }

    /** What the transactions screen reads on arrival, and with the first account chosen. */
    private static void readTheLedger(MobileRecorder record, Caller who, MobileDemo demo) {
        record.send(who, "GET", "/api/auth/me", null);
        record.send(who, "GET", ACCOUNTS, null);
        record.send(who, "GET", CATEGORIES, null);
        record.send(who, "GET", LEDGER, null);
        record.send(who, "GET", LEDGER + "&accountId=" + demo.account("acc-0001"), null);
    }

    private static void ok204(ApiResponse response) {
        if (response.status() != 204) {
            throw new AssertionError("a demo setup step failed: " + response.body());
        }
    }

    /**
     * July and August of {@link #DEMO_LEDGER}, with a payee on every row, a fourth account in euros
     * for the cross-currency transfer, and July's and August's transfers.
     */
    private MobileDemo seedTheMobileLedger(Household household) {
        ApiClient owner = household.owner();
        Map<String, UUID> categories = seedDemoCategories(owner);
        Map<String, UUID> accounts = seedDemoAccounts(owner);
        accounts.put("acc-0004", demoAccount(owner, "Berlin Savings", null, "SAVINGS", "1200.00"));

        Map<String, UUID> rows = new LinkedHashMap<>();
        for (String[] row : DEMO_LEDGER) {
            if (row[0].compareTo("2026-07-01") < 0) {
                continue;
            }
            String payee = MOBILE_PAYEES.get(row[3] == null ? "none" : row[3]);
            String note = "2026-08-12".equals(row[0]) ? "\"big shop\"" : "null";
            ApiResponse created =
                    owner.post(
                            "/api/transactions",
                            """
                            {"accountId":"%s","date":"%s","amount":"%s","categoryId":%s,
                             "status":"%s","payeeName":"%s","note":%s}
                            """
                                    .formatted(
                                            accounts.get(row[2]),
                                            row[0],
                                            row[1],
                                            row[3] == null
                                                    ? "null"
                                                    : "\"" + categories.get(row[3]) + "\"",
                                            row[4],
                                            payee,
                                            note));
            if (created.status() != 201) {
                throw new AssertionError("could not seed a mobile transaction: " + created.body());
            }
            UUID id = UUID.fromString(created.stringField("id"));
            switch (row[0] + " " + row[1]) {
                case "2026-08-20 -92.15" -> rows.put("pending-groceries", id);
                case "2026-08-03 -86.40" -> rows.put("groceries-aug-03", id);
                case "2026-08-09 -41.20" -> rows.put("dinner-aug-09", id);
                default -> {}
            }
        }
        UUID august = null;
        Map<UUID, String> stable = new LinkedHashMap<>();
        for (String[] transfer : DEMO_TRANSFERS) {
            if (transfer[0].compareTo("2026-07-01") < 0) {
                continue;
            }
            UUID made =
                    transfer(
                            owner,
                            accounts.get(transfer[2]),
                            accounts.get(transfer[3]),
                            transfer[0],
                            transfer[1]);
            if (transfer[0].startsWith("2026-08")) {
                august = made;
                stable.put(made, "tgr-0001");
            } else {
                stable.put(made, "tgr-" + transfer[0]);
            }
        }
        ok200(owner.post("/api/categories/" + categories.get("cat-0009") + "/archive", null));

        categories.forEach((name, id) -> stable.put(id, name));
        accounts.forEach((name, id) -> stable.put(id, name));
        nameRows(stable);
        return new MobileDemo(accounts, categories, rows, august, stable);
    }

    /**
     * Ids to stable names, timestamps to the demo instant, and the two values a fixture cannot pin
     * by construction — a problem's correlation id and the JDK in a browser's user agent.
     */
    private static String canonicaliseMobile(String document, Map<UUID, String> stable) {
        String pinned =
                document.replaceAll(
                                "\"(createdAt|updatedAt|lastUsedAt|expiresAt|joinedAt)\"(\\s*):(\\s*)\"[^\"]+\"",
                                "\"$1\"$2:$3\"" + DEMO_TODAY + "\"")
                        .replaceAll(
                                "\"correlationId\"(\\s*):(\\s*)\"[^\"]+\"",
                                "\"correlationId\"$1:$2\"fixture-correlation\"")
                        .replaceAll("Java-http-client/[0-9._A-Za-z-]+", "Java-http-client");
        return canonicalise(pinned, new Fixture(Map.of(), null, stable));
    }

    /**
     * A second account in euros with a little history beside a dollar one, so the currency switcher
     * has two complete reports to switch between.
     */
    private void seedTwoCurrencies(Household household, Map<UUID, String> stable) {
        ApiClient owner = household.owner();
        UUID dollars = demoAccount(owner, "Everyday Checking", "4417", "CHECKING", "0.00");
        UUID euros = demoAccount(owner, "Berlin Savings", null, "SAVINGS", "1200.00");
        UUID salary = seededCategoryId("Salary");
        UUID groceries = seededCategoryId("Groceries");
        UUID eating = seededCategoryId("Eating Out");
        String[][] rows = {
            {"2026-06-01", "3200.00", "USD", "salary"},
            {"2026-06-04", "-212.40", "USD", "groceries"},
            {"2026-07-01", "3200.00", "USD", "salary"},
            {"2026-07-03", "-198.60", "USD", "groceries"},
            {"2026-07-11", "-61.20", "USD", "eating"},
            {"2026-06-20", "450.00", "EUR", "salary"},
            {"2026-07-08", "-84.30", "EUR", "groceries"},
            {"2026-07-20", "450.00", "EUR", "salary"},
            {"2026-07-22", "-37.50", "EUR", "eating"},
        };
        for (String[] row : rows) {
            UUID category =
                    switch (row[3]) {
                        case "salary" -> salary;
                        case "groceries" -> groceries;
                        default -> eating;
                    };
            createTransaction(
                    owner, "USD".equals(row[2]) ? dollars : euros, row[0], row[1], category);
        }
        stable.put(dollars, "acc-8001");
        stable.put(euros, "acc-8002");
        stable.put(salary, "cat-8002");
        stable.put(groceries, "cat-8006");
        stable.put(eating, "cat-8008");
    }

    private UUID demoAccount(
            ApiClient owner, String name, String lastFour, String type, String openingBalance) {
        String currency = name.startsWith("Berlin") ? "EUR" : "USD";
        ApiResponse created =
                owner.post(
                        "/api/accounts",
                        """
                        {"name":"%s","lastFour":%s,"type":"%s","currency":"%s",
                         "openingBalance":"%s"}
                        """
                                .formatted(
                                        name,
                                        lastFour == null ? "null" : "\"" + lastFour + "\"",
                                        type,
                                        currency,
                                        openingBalance));
        if (created.status() != 201) {
            throw new AssertionError("could not seed a demo account: " + created.body());
        }
        return UUID.fromString(created.stringField("id"));
    }

    private UUID transfer(ApiClient owner, UUID from, UUID to, String date, String amount) {
        ApiResponse created =
                owner.post(
                        "/api/transfers",
                        """
                        {"fromAccountId":"%s","toAccountId":"%s","date":"%s","amount":"%s"}
                        """
                                .formatted(from, to, date, amount));
        if (created.status() != 201) {
            throw new AssertionError("could not seed a demo transfer: " + created.body());
        }
        return UUID.fromString(created.stringField("id"));
    }

    private static void ok200(ApiResponse response) {
        if (response.status() != 200) {
            throw new AssertionError("a demo setup step failed: " + response.body());
        }
    }

    /**
     * The responses, and the ledger they were summed from, as one TypeScript module.
     *
     * <p>A CSV body is a JSON string with every non-ASCII character escaped — the byte-order mark
     * first among them — so the committed file holds no invisible character a reviewer could miss.
     */
    private static String renderReports(Map<String, String> responses) {
        StringBuilder out = new StringBuilder(render(responses, "REPORT_API"));
        out.append(
                """

                // The ledger the reports above were summed from: every row, transfers included.
                // demo-data.ts checks at load that each of DEMO_TRANSACTIONS is one of these.
                export const REPORT_LEDGER = [
                """);
        for (String[] row : DEMO_LEDGER) {
            out.append(
                    "  { date: \"%s\", amount: \"%s\", account: \"%s\", category: %s, status: \"%s\", transfer: false },\n"
                            .formatted(
                                    row[0],
                                    row[1],
                                    row[2],
                                    row[3] == null ? "null" : "\"" + row[3] + "\"",
                                    row[4]));
        }
        for (String[] t : DEMO_TRANSFERS) {
            out.append(
                    "  { date: \"%s\", amount: \"-%s\", account: \"%s\", category: null, status: \"CLEARED\", transfer: true },\n"
                            .formatted(t[0], t[1], t[2]));
            out.append(
                    "  { date: \"%s\", amount: \"%s\", account: \"%s\", category: null, status: \"CLEARED\", transfer: true },\n"
                            .formatted(t[0], t[1], t[3]));
        }
        return out.append("] as const;\n").toString();
    }

    /** A JSON string literal, ASCII only. */
    private static String jsonString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /**
     * A TypeScript module rather than a JSON one.
     *
     * <p>Not a style choice: Playwright runs the harness's TypeScript through its own loader, which
     * emits ESM, and an ESM {@code import} of a {@code .json} file needs an import attribute the
     * harness's module target does not supply — so a JSON fixture typechecks and then fails at run
     * time with {@code needs an import attribute of "type: json"}. A generated {@code .ts} file is
     * an ordinary import everywhere.
     *
     * <p>Prettier checks this file, so it is written in Prettier's own shape: two-space indent,
     * double quotes left as the JSON has them, and one key per line.
     */
    private static String render(Map<String, String> responses, String exportName) {
        StringBuilder out =
                new StringBuilder(
                        """
                        // GENERATED by backend DemoFixtureIT. Do not edit by hand.
                        //
                        // These are the real responses of the real application, read back over
                        // HTTP with the clock fixed, with the ids canonicalised. The same test
                        // fails the backend gate if this file stops matching what the application
                        // returns, because the user guide is captured from it: a figure edited
                        // here would be documented as the app's arithmetic without being it.
                        //
                        // Regenerate:
                        //   mvn -f backend/pom.xml test -Dtest=DemoFixtureIT -Dfixtures.write=true
                        // then read the diff and recapture the guide screenshots it changes.

                        export const %s = {
                        """
                                .formatted(exportName));
        int remaining = responses.size();
        for (Map.Entry<String, String> entry : responses.entrySet()) {
            out.append("  \"").append(entry.getKey()).append("\": ");
            out.append(entry.getValue().strip());
            out.append(--remaining == 0 ? "\n" : ",\n");
        }
        return out.append("} as const;\n").toString();
    }

    /**
     * Replaces every generated id with a stable one, so regenerating does not churn the file.
     *
     * <p>Ids the fixtures already have a name for keep it; anything else becomes {@code gen-NNNN}
     * in order of first appearance. Longest-first so one id cannot be rewritten inside another.
     */
    private static String canonicalise(String document, Fixture seeded) {
        Map<UUID, String> names = new LinkedHashMap<>(seeded.stableIds());
        java.util.regex.Matcher ids =
                java.util.regex.Pattern.compile(
                                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                        .matcher(document);
        int next = 1;
        while (ids.find()) {
            UUID found = UUID.fromString(ids.group());
            if (!names.containsKey(found)) {
                names.put(found, String.format("gen-%04d", next++));
            }
        }
        Map<String, String> byLength = new TreeMap<>(java.util.Comparator.naturalOrder());
        String canonical = document;
        for (Map.Entry<UUID, String> name : names.entrySet()) {
            byLength.put(name.getKey().toString(), name.getValue());
        }
        for (Map.Entry<String, String> replacement : byLength.entrySet()) {
            canonical = canonical.replace(replacement.getKey(), replacement.getValue());
        }
        return canonical;
    }
}
