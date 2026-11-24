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
