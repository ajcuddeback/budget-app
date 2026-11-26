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
     *
     * <p><b>February to July, and not August.</b> The demo's today is 20 August, and the user
     * guide's walkthrough logs a payment on it. With August already paid, that payment was a second
     * one in the month, so the guide showed it charged a second month of interest — the defect
     * ADR-0040 removes. With August unpaid, the walkthrough logs August's payment and carries
     * August's interest, which is what it describes.
     */
    private void payDownTheVisa(Household household, UUID visa) {
        for (int month = 2; month <= 7; month++) {
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
    private static final String[][] DEMO_LEDGER = {
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
    private static final String[][] DEMO_TRANSFERS = {
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

        Map<String, UUID> accounts = new LinkedHashMap<>();
        accounts.put(
                "acc-0001", demoAccount(owner, "Everyday Checking", "4417", "CHECKING", "1641.75"));
        accounts.put("acc-0002", demoAccount(owner, "Emergency Fund", null, "SAVINGS", "5620.00"));
        accounts.put(
                "acc-0003", demoAccount(owner, "Travel Card", "8820", "CREDIT_CARD", "-215.24"));

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
