package com.budgetowl.api;

import com.budgetowl.api.DemoFixtureIT.Caller;
import com.budgetowl.api.DemoFixtureIT.MobileRecorder;
import com.budgetowl.api.DemoFixtureIT.SettableClock;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The phone's Bills &amp; income, Debt plan and Goals fixtures (slice 15, phase 2), <b>from the
 * real application</b>.
 *
 * <p>The mobile twin of {@link DemoFixtureIT}'s mobile section, in a class of its own so the areas
 * phase 2 builds in parallel each record their own file. Every response in {@code
 * mobile/test/fixtures/{bills,debt,goals}.generated.json} is one the API returned to the request
 * the app makes: a "what's left" figure, a payoff month, a payment's interest split or a goal's
 * projection drawn in a golden is the server's arithmetic, never a hand's.
 *
 * <p><b>One household, told three ways.</b> Each test seeds the same "plans household" through the
 * API — bills on every schedule, a fortnightly wage, the designs' three debts with the car payment
 * as a bill row, and the designs' goals — then records one area's screens in a <b>linear chain of
 * states</b>, as the phase-1 recorder does: each write moves the real server, and the reads after
 * it are recorded in the state it leaves, so a widget test that starts in a state sees exactly what
 * the API would have shown it.
 *
 * <p><b>The clock.</b> Phone tokens are issued at real time, because a token issued "in August
 * 2026" would expire before the database says it was created. Then the clock moves to the demo
 * instant, and everything that depends on today — overdue occurrences, this month's goal plan, a
 * payoff month, an estimated balance — is seeded and read there. Every change to the payoff plan
 * moves the clock on by a second, because plan snapshots are unique on the instant they were taken.
 *
 * <p>Regenerate: {@code mvn -f backend -B verify -Dtest=none
 * -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='MobilePlansFixtureIT*' -Dfixtures.write=true}.
 * Never edit a generated file by hand.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@Import(DemoFixtureIT.DemoClock.class)
class MobilePlansFixtureIT extends ApiTestBase {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static final String PERIOD = "2026-08";

    private static final String TODAY = "2026-08-20";

    private static final String BILLS = "/api/bills?period=%s&includeInactive=true";

    private static final String INCOME = "/api/income?period=%s&includeInactive=true";

    private static final String SUMMARY = "/api/periods/%s/summary";

    /** The bills the debt dialog offers: this month's, active only, as the web asks. */
    private static final String ACTIVE_BILLS =
            "/api/bills?period=" + PERIOD + "&includeInactive=false";

    private static final String DEBTS = "/api/debts?includeArchived=false";

    private static final String PLAN = "/api/debt-plan";

    private static final String GOALS = "/api/goals?includeArchived=true&page=0&size=200";

    private static final String FIRST_PAGE = "?page=0&size=50";

    @Autowired private SettableClock clock;

    /** The ids the household was seeded with, by the stable names the fixtures use. */
    private record Plans(Map<String, UUID> ids, Map<UUID, String> stable) {

        String id(String name) {
            UUID id = ids.get(name);
            if (id == null) {
                throw new AssertionError("nothing seeded as " + name);
            }
            return id.toString();
        }
    }

    /** The two phones a scenario drives: the owner's and a viewer's, with their raw tokens. */
    private record Phones(Caller owner, Caller viewer, String ownerToken, String viewerToken) {

        /** Neither token may reach a committed file: each is replaced by the name tests use. */
        void hide(MobileRecorder record) {
            record.secret(ownerToken, "fixture-token-owner");
            record.secret(viewerToken, "fixture-token-viewer");
        }
    }

    // ======================================================================= bills & income

    @Test
    void the_committed_mobile_bills_fixtures_are_what_the_application_returns() throws IOException {
        clock.moveTo(Instant.now());
        Household household = seedHousehold("USD");
        Phones phones = issuePhones();
        clock.moveTo(DemoFixtureIT.DEMO_TODAY);

        MobileRecorder record = new MobileRecorder();
        phones.hide(record);
        record.in("*");
        record.send(Caller.stranger(anonymous()), "GET", "/api/meta", null);

        // Before anything is set up: every block's empty state.
        record.in("empty");
        readHome(record, phones.owner());
        readBills(record, phones.owner(), PERIOD);

        Plans plans = seedThePlans(household);
        Caller owner = phones.owner();

        record.in("plans");
        readHome(record, owner);
        readBills(record, owner, PERIOD);
        readBills(record, owner, "2026-07");
        readBills(record, owner, "2026-09");

        record.in("viewer");
        readHome(record, phones.viewer());
        readBills(record, phones.viewer(), PERIOD);
        // A VIEWER's write, if one is ever sent: the API's 403, translated on the phone.
        record.send(
                phones.viewer(),
                "PUT",
                occurrence(plans, "bil-0003", "2026-08-18"),
                "{\"status\":\"PAID\"}");

        String bills = "/api/bills/";
        record.in("plans");
        long phoneVersion = version(owner, bills + plans.id("bil-0002") + "?period=" + PERIOD);
        billsStep(
                record,
                owner,
                "plans",
                "paid",
                "PUT",
                occurrence(plans, "bil-0003", "2026-08-18"),
                "{\"status\":\"PAID\"}");
        billsStep(
                record,
                owner,
                "paid",
                "paid-other",
                "PUT",
                occurrence(plans, "bil-0005", "2026-08-17"),
                "{\"status\":\"PAID\",\"actualAmount\":\"14.50\",\"paidDate\":\"" + TODAY + "\"}");
        billsStep(
                record,
                owner,
                "paid-other",
                "skipped",
                "PUT",
                occurrence(plans, "bil-0004", "2026-08-25"),
                "{\"status\":\"SKIPPED\"}");
        billsStep(
                record,
                owner,
                "skipped",
                "marked-due",
                "PUT",
                occurrence(plans, "bil-0001", "2026-08-01"),
                "{\"status\":\"DUE\"}");
        billsStep(
                record,
                owner,
                "marked-due",
                "forgotten",
                "DELETE",
                occurrence(plans, "bil-0002", "2026-08-12"),
                null);
        billsStep(
                record,
                owner,
                "forgotten",
                "recorded",
                "PUT",
                occurrence(plans, "bil-0008", TODAY),
                "{\"status\":\"PAID\",\"actualAmount\":\"25\",\"paidDate\":\"" + TODAY + "\"}");
        ApiResponse water =
                billsStep(
                        record,
                        owner,
                        "recorded",
                        "bill-added",
                        "POST",
                        "/api/bills",
                        """
                        {"name":"Water","expectedAmount":"38.50",
                         "schedule":{"kind":"MONTHLY","dayOfMonth":22}}
                        """);
        plans.stable().put(id(water), "bil-0010");
        long phone = version(owner, bills + plans.id("bil-0002") + "?period=" + PERIOD);
        billsStep(
                record,
                owner,
                "bill-added",
                "bill-edited",
                "PATCH",
                bills + plans.id("bil-0002"),
                "{\"version\":" + phone + ",\"expectedAmount\":\"48\"}");
        long gym = version(owner, bills + plans.id("bil-0005") + "?period=" + PERIOD);
        billsStep(
                record,
                owner,
                "bill-edited",
                "bill-retired",
                "PATCH",
                bills + plans.id("bil-0005"),
                "{\"version\":" + gym + ",\"active\":false}");
        long streaming = version(owner, bills + plans.id("bil-0009") + "?period=" + PERIOD);
        billsStep(
                record,
                owner,
                "bill-retired",
                "bill-restored",
                "PATCH",
                bills + plans.id("bil-0009"),
                "{\"version\":" + streaming + ",\"active\":true}");
        billsStep(
                record,
                owner,
                "bill-restored",
                "bill-deleted",
                "DELETE",
                bills + plans.id("bil-0007"),
                null);

        String income = "/api/income/";
        ApiResponse tutoring =
                billsStep(
                        record,
                        owner,
                        "bill-deleted",
                        "income-added",
                        "POST",
                        "/api/income",
                        """
                        {"name":"Tutoring","amount":"60",
                         "schedule":{"kind":"WEEKLY","dayOfWeek":"SATURDAY"}}
                        """);
        UUID tutor = id(tutoring);
        plans.stable().put(tutor, "inc-0004");
        billsStep(
                record,
                owner,
                "income-added",
                "income-edited",
                "PATCH",
                income + tutor,
                "{\"version\":"
                        + version(owner, income + tutor + "?period=" + PERIOD)
                        + ",\"amount\":\"65\"}");
        long benefit = version(owner, income + plans.id("inc-0002") + "?period=" + PERIOD);
        billsStep(
                record,
                owner,
                "income-edited",
                "income-retired",
                "PATCH",
                income + plans.id("inc-0002"),
                "{\"version\":" + benefit + ",\"active\":false}");
        long freelance = version(owner, income + plans.id("inc-0003") + "?period=" + PERIOD);
        billsStep(
                record,
                owner,
                "income-retired",
                "income-restored",
                "PATCH",
                income + plans.id("inc-0003"),
                "{\"version\":" + freelance + ",\"active\":true}");
        billsStep(
                record, owner, "income-restored", "income-deleted", "DELETE", income + tutor, null);
        billsStep(
                record,
                owner,
                "income-deleted",
                "unmapped",
                "DELETE",
                "/api/charge-rules/" + plans.id("rul-0001"),
                null);

        // A phone still holding the phone bill as "plans" read it: somebody else's edit got there
        // first, so its PATCH is the 409 a stale version gets.
        record.in("plans");
        record.send(
                owner,
                "PATCH",
                bills + plans.id("bil-0002"),
                "{\"version\":" + phoneVersion + ",\"expectedAmount\":\"48\"}");

        write(record, plans, "bills");
    }

    private ApiResponse billsStep(
            MobileRecorder record,
            Caller who,
            String from,
            String to,
            String method,
            String path,
            String body) {
        record.in(from);
        ApiResponse response = record.sendThen(to, who, method, path, body);
        if (response.status() >= 300) {
            throw new AssertionError(method + " " + path + " failed: " + response.body());
        }
        record.in(to);
        readHome(record, who);
        readBills(record, who, PERIOD);
        return response;
    }

    /** What the Bills tab reads for one month, and the lists its dialogs offer. */
    private static void readBills(MobileRecorder record, Caller who, String period) {
        record.send(who, "GET", BILLS.formatted(period), null);
        record.send(who, "GET", INCOME.formatted(period), null);
        record.send(who, "GET", SUMMARY.formatted(period), null);
    }

    private static String occurrence(Plans plans, String bill, String date) {
        return "/api/bills/" + plans.id(bill) + "/occurrences/" + date;
    }

    // ============================================================================ debt plan

    @Test
    void the_committed_mobile_debt_fixtures_are_what_the_application_returns() throws IOException {
        clock.moveTo(Instant.now());
        Household household = seedHousehold("USD");
        Phones phones = issuePhones();
        clock.moveTo(DemoFixtureIT.DEMO_TODAY);

        MobileRecorder record = new MobileRecorder();
        phones.hide(record);
        record.in("*");
        record.send(Caller.stranger(anonymous()), "GET", "/api/meta", null);

        record.in("empty");
        readHome(record, phones.owner());
        readDebtPlan(record, phones.owner());

        Plans plans = seedThePlans(household);
        Caller owner = phones.owner();
        List<String> debts = new ArrayList<>(List.of("deb-0001", "deb-0002", "deb-0003"));

        record.in("plans");
        readHome(record, owner);
        readDebtPlan(record, owner);
        readDebtDetails(record, owner, plans, debts);
        // What the log-a-payment sheet asks before anything is recorded: the Visa at what the plan
        // gives it, and at that plus the sheet's one-tap fifty, on the date the sheet opens with:
        // which month's interest a payment carries depends on its date (ADR-0043).
        for (String amount : List.of("445", "495")) {
            record.send(
                    owner,
                    "POST",
                    "/api/debts/" + plans.id("deb-0001") + "/payments/preview",
                    "{\"amount\":\"" + amount + "\",\"paidDate\":\"2026-08-20\"}");
        }
        // And back-dated into February, which has no payment: it would carry that month's interest.
        record.send(
                owner,
                "POST",
                "/api/debts/" + plans.id("deb-0001") + "/payments/preview",
                "{\"amount\":\"445\",\"paidDate\":\"2026-02-25\"}");

        record.in("viewer");
        readHome(record, phones.viewer());
        readDebtPlan(record, phones.viewer());
        readDebtDetails(record, phones.viewer(), plans, debts);
        record.send(
                phones.viewer(),
                "PUT",
                PLAN,
                "{\"version\":" + planVersion(owner) + ",\"strategy\":\"SNOWBALL\"}");

        String visa = "/api/debts/" + plans.id("deb-0001");
        debtStep(
                record,
                owner,
                plans,
                debts,
                "plans",
                "payment-logged",
                "POST",
                visa + "/payments",
                "{\"paidDate\":\""
                        + TODAY
                        + "\",\"amount\":\"495\",\"paidFromAccountId\":\""
                        + plans.id("acc-0001")
                        + "\"}");
        long visaVersion = detailVersion(owner, visa);
        debtStep(
                record,
                owner,
                plans,
                debts,
                "payment-logged",
                "terms-edited",
                "PATCH",
                visa,
                "{\"version\":" + visaVersion + ",\"servicer\":\"Chase Bank\"}");

        // A debt added three months after its statement: ESTIMATED, so its page asks to confirm.
        ApiResponse store =
                debtStep(
                        record,
                        owner,
                        plans,
                        debts,
                        "terms-edited",
                        "debt-added",
                        "POST",
                        "/api/debts",
                        """
                        {"name":"Store card","kind":"CREDIT_CARD","servicer":"Argos",
                         "balance":"1240","balanceDate":"2026-05-20","apr":"29.9",
                         "minimumPayment":"45"}
                        """);
        UUID storeCard = debtId(store);
        plans.ids().put("deb-0004", storeCard);
        plans.stable().put(storeCard, "deb-0004");
        debts.add("deb-0004");
        record.in("debt-added");
        readDebtDetails(record, owner, plans, debts);

        debtStep(
                record,
                owner,
                plans,
                debts,
                "debt-added",
                "estimate-corrected",
                "POST",
                "/api/debts/" + storeCard + "/balance",
                "{\"balance\":\"1180\",\"balanceDate\":\"" + TODAY + "\"}");

        // Fourteen months unconfirmed: past the cap, so the page asks for a statement instead.
        ApiResponse overdraft =
                debtStep(
                        record,
                        owner,
                        plans,
                        debts,
                        "estimate-corrected",
                        "stale-added",
                        "POST",
                        "/api/debts",
                        """
                        {"name":"Old overdraft","kind":"CREDIT_CARD","servicer":"Monzo",
                         "balance":"860","balanceDate":"2025-06-20","apr":"39.9",
                         "minimumPayment":"25"}
                        """);
        UUID old = debtId(overdraft);
        plans.ids().put("deb-0005", old);
        plans.stable().put(old, "deb-0005");
        debts.add("deb-0005");
        record.in("stale-added");
        readDebtDetails(record, owner, plans, debts);
        debtStep(
                record,
                owner,
                plans,
                debts,
                "stale-added",
                "stale-confirmed",
                "POST",
                "/api/debts/" + old + "/balance",
                "{\"balance\":\"860\",\"balanceDate\":\"" + TODAY + "\"}");

        // The plan itself: the order of attack, the household's own order, and the amount.
        planStep(
                record,
                owner,
                plans,
                debts,
                "stale-confirmed",
                "snowball",
                "{\"version\":%d,\"strategy\":\"SNOWBALL\"}");
        planStep(
                record,
                owner,
                plans,
                debts,
                "snowball",
                "avalanche",
                "{\"version\":%d,\"strategy\":\"AVALANCHE\"}");
        List<String> rungs = rungOrder(owner, plans);
        planStep(
                record,
                owner,
                plans,
                debts,
                "avalanche",
                "custom",
                "{\"version\":%d,\"strategy\":\"CUSTOM\",\"customOrder\":" + ids(rungs) + "}");
        List<String> moved = new ArrayList<>(rungs);
        moved.set(0, rungs.get(1));
        moved.set(1, rungs.get(0));
        planStep(
                record,
                owner,
                plans,
                debts,
                "custom",
                "custom-moved",
                "{\"version\":%d,\"strategy\":\"CUSTOM\",\"customOrder\":" + ids(moved) + "}");
        planStep(
                record,
                owner,
                plans,
                debts,
                "custom-moved",
                "amount",
                "{\"version\":%d,\"monthlyAmount\":\"2000\"}");
        planStep(
                record,
                owner,
                plans,
                debts,
                "amount",
                "below",
                "{\"version\":%d,\"monthlyAmount\":\"600\"}");

        write(record, plans, "debt");
    }

    private ApiResponse debtStep(
            MobileRecorder record,
            Caller who,
            Plans plans,
            List<String> debts,
            String from,
            String to,
            String method,
            String path,
            String body) {
        record.in(from);
        ApiResponse response = record.sendThen(to, who, method, path, body);
        if (response.status() >= 300) {
            throw new AssertionError(method + " " + path + " failed: " + response.body());
        }
        record.in(to);
        readHome(record, who);
        readDebtPlan(record, who);
        readDebtDetails(record, who, plans, debts);
        return response;
    }

    private void planStep(
            MobileRecorder record,
            Caller who,
            Plans plans,
            List<String> debts,
            String from,
            String to,
            String body) {
        // Plan snapshots are unique on the instant they were taken.
        clock.moveTo(clock.instant().plusSeconds(1));
        String shown = whatIfAmount(JSON.readTree(who.client().get(PLAN).body()));
        debtStep(
                record,
                who,
                plans,
                debts,
                from,
                to,
                "PUT",
                PLAN,
                body.formatted(planVersion(who.client())));
        // A new monthly amount: the phone re-reads the what-if it was showing as the write lands,
        // before the new plan moves it on to the new amount's. Answered as the real API answers it.
        String now = whatIfAmount(JSON.readTree(who.client().get(PLAN).body()));
        if (shown != null && !shown.equals(now)) {
            record.in(to);
            record.send(who, "GET", PLAN + "/projection?monthlyAmount=" + shown, null);
        }
    }

    /**
     * The what-if the page asks: the plan's amount plus fifty, at the server's scale, as the web's
     * exact addition writes it. Null with no debt to ask about.
     */
    private static String whatIfAmount(JsonNode plan) {
        if (plan.get("debtCount").asLong() == 0) {
            return null;
        }
        return new BigDecimal(plan.get("monthlyAmount").asString())
                .add(new BigDecimal("50"))
                .setScale(4)
                .toPlainString();
    }

    /** The plan page: the plan, the debts, the what-if, the summary and the bills it offers. */
    private static void readDebtPlan(MobileRecorder record, Caller who) {
        ApiResponse plan = record.send(who, "GET", PLAN, null);
        record.send(who, "GET", DEBTS, null);
        record.send(who, "GET", ACTIVE_BILLS, null);
        record.send(who, "GET", SUMMARY.formatted(PERIOD), null);
        // The what-if: a read that stores nothing.
        String more = whatIfAmount(JSON.readTree(plan.body()));
        if (more != null) {
            record.send(who, "GET", PLAN + "/projection?monthlyAmount=" + more, null);
        }
    }

    private static void readDebtDetails(
            MobileRecorder record, Caller who, Plans plans, List<String> debts) {
        for (String debt : debts) {
            String path = "/api/debts/" + plans.id(debt);
            record.send(who, "GET", path, null);
            record.send(who, "GET", path + "/payments" + FIRST_PAGE, null);
        }
    }

    private static long planVersion(ApiClient who) {
        return version(who, PLAN);
    }

    private static long planVersion(Caller who) {
        return planVersion(who.client());
    }

    private static long detailVersion(Caller who, String path) {
        return JSON.readTree(who.client().get(path).body()).get("debt").get("version").asLong();
    }

    /** The ranked debts in the order the plan has them now, rung 1 first. */
    private static List<String> rungOrder(Caller who, Plans plans) {
        JsonNode debts = JSON.readTree(who.client().get(DEBTS).body()).get("debts");
        Map<Long, String> byRung = new java.util.TreeMap<>();
        for (JsonNode debt : debts) {
            if (!debt.get("rung").isNull()) {
                byRung.put(debt.get("rung").asLong(), debt.get("id").asString());
            }
        }
        return new ArrayList<>(byRung.values());
    }

    private static String ids(List<String> order) {
        StringBuilder out = new StringBuilder("[");
        for (String id : order) {
            out.append(out.length() == 1 ? "" : ",").append('"').append(id).append('"');
        }
        return out.append(']').toString();
    }

    // ================================================================================ goals

    @Test
    void the_committed_mobile_goal_fixtures_are_what_the_application_returns() throws IOException {
        clock.moveTo(Instant.now());
        Household household = seedHousehold("USD");
        Phones phones = issuePhones();
        clock.moveTo(DemoFixtureIT.DEMO_TODAY);

        MobileRecorder record = new MobileRecorder();
        phones.hide(record);
        record.in("*");
        record.send(Caller.stranger(anonymous()), "GET", "/api/meta", null);

        record.in("empty");
        readHome(record, phones.owner());
        record.send(phones.owner(), "GET", GOALS, null);

        Plans plans = seedThePlans(household);
        Caller owner = phones.owner();
        List<String> goals =
                new ArrayList<>(List.of("gol-0001", "gol-0002", "gol-0003", "gol-0004"));

        record.in("plans");
        readGoals(record, owner, plans, goals);
        // The emergency fund's history runs past one page: "Show more" reads the second.
        record.send(
                owner,
                "GET",
                "/api/goals/" + plans.id("gol-0002") + "/contributions?page=1&size=50",
                null);
        // The add dialog as the test fills it in, and the edit and plan dialogs as they open.
        record.send(owner, "POST", "/api/goals/projection-preview", "{\"targetAmount\":\"900\"}");
        for (String goal : goals) {
            record.send(
                    owner, "POST", "/api/goals/" + plans.id(goal) + "/projection-preview", "{}");
        }
        for (String amount : List.of("110", "165")) {
            record.send(
                    owner,
                    "POST",
                    "/api/goals/" + plans.id("gol-0001") + "/projection-preview",
                    "{\"monthlyAmount\":\"" + amount + "\"}");
        }
        record.send(
                owner,
                "POST",
                "/api/goals/" + plans.id("gol-0001") + "/projection-preview",
                "{\"targetAmount\":\"4000\"}");

        record.in("viewer");
        readGoals(record, phones.viewer(), plans, goals);
        record.send(
                phones.viewer(),
                "POST",
                "/api/goals/" + plans.id("gol-0001") + "/contributions",
                "{\"amount\":\"110\",\"date\":\"" + TODAY + "\"}");

        ApiResponse bike =
                goalStep(
                        record,
                        owner,
                        plans,
                        goals,
                        "plans",
                        "goal-added",
                        "POST",
                        "/api/goals",
                        "{\"name\":\"Bike\",\"targetAmount\":\"900\"}");
        UUID bikeId = id(bike);
        plans.ids().put("gol-0005", bikeId);
        plans.stable().put(bikeId, "gol-0005");
        goals.add("gol-0005");
        record.in("goal-added");
        readGoals(record, owner, plans, goals);

        String japan = "/api/goals/" + plans.id("gol-0001");
        goalStep(
                record,
                owner,
                plans,
                goals,
                "goal-added",
                "set-aside",
                "POST",
                japan + "/contributions",
                "{\"amount\":\"110\",\"date\":\"" + TODAY + "\"}");
        goalStep(
                record,
                owner,
                plans,
                goals,
                "set-aside",
                "taken-out",
                "POST",
                "/api/goals/" + plans.id("gol-0002") + "/contributions",
                "{\"amount\":\"-50\",\"date\":\"" + TODAY + "\",\"note\":\"Vet\"}");
        record.in("taken-out");
        record.send(owner, "POST", japan + "/projection-preview", "{}");
        record.send(owner, "POST", japan + "/projection-preview", "{\"targetAmount\":\"4000\"}");
        goalStep(
                record,
                owner,
                plans,
                goals,
                "taken-out",
                "goal-edited",
                "PATCH",
                japan,
                "{\"version\":" + version(owner, japan) + ",\"targetAmount\":\"4000\"}");
        record.in("goal-edited");
        for (String amount : List.of("110", "165")) {
            record.send(
                    owner,
                    "POST",
                    japan + "/projection-preview",
                    "{\"monthlyAmount\":\"" + amount + "\"}");
        }
        goalStep(
                record,
                owner,
                plans,
                goals,
                "goal-edited",
                "plan-changed",
                "PUT",
                japan + "/contribution-plan/" + PERIOD,
                "{\"monthlyAmount\":\"165\",\"version\":" + version(owner, japan) + "}");
        String newest =
                JSON.readTree(owner.client().get(japan + "/contributions" + FIRST_PAGE).body())
                        .get("content")
                        .get(0)
                        .get("id")
                        .asString();
        goalStep(
                record,
                owner,
                plans,
                goals,
                "plan-changed",
                "contribution-removed",
                "DELETE",
                japan + "/contributions/" + newest,
                null);
        String laptop = "/api/goals/" + plans.id("gol-0003");
        goalStep(
                record,
                owner,
                plans,
                goals,
                "contribution-removed",
                "archived",
                "POST",
                laptop + "/archive",
                "{}");
        goalStep(
                record,
                owner,
                plans,
                goals,
                "archived",
                "unarchived",
                "POST",
                laptop + "/unarchive",
                "{}");
        goals.remove("gol-0005");
        goalStep(
                record,
                owner,
                plans,
                goals,
                "unarchived",
                "deleted",
                "DELETE",
                "/api/goals/" + bikeId,
                null);

        write(record, plans, "goals");
    }

    private ApiResponse goalStep(
            MobileRecorder record,
            Caller who,
            Plans plans,
            List<String> goals,
            String from,
            String to,
            String method,
            String path,
            String body) {
        record.in(from);
        ApiResponse response = record.sendThen(to, who, method, path, body);
        if (response.status() >= 300) {
            throw new AssertionError(method + " " + path + " failed: " + response.body());
        }
        record.in(to);
        readGoals(record, who, plans, goals);
        return response;
    }

    /** The goals list, and every goal's page: the goal, its plan and its history. */
    private static void readGoals(
            MobileRecorder record, Caller who, Plans plans, List<String> goals) {
        readHome(record, who);
        record.send(who, "GET", GOALS, null);
        for (String goal : goals) {
            String path = "/api/goals/" + plans.id(goal);
            record.send(who, "GET", path, null);
            record.send(who, "GET", path + "/contribution-plan" + FIRST_PAGE, null);
            record.send(who, "GET", path + "/contributions" + FIRST_PAGE, null);
        }
    }

    // =========================================================================== the household

    /** The demo household's people, in {@code currency}: an owner, a member and a viewer. */
    private Household seedHousehold(String currency) {
        ApiResponse setup =
                browser()
                        .post(
                                "/api/setup/first-user",
                                """
                                {"email":"%s","displayName":"Ada","password":"%s",
                                 "householdName":"Rivera Household","baseCurrency":"%s"}
                                """
                                        .formatted(OWNER_EMAIL, OWNER_SECRET, currency));
        if (setup.status() != 201) {
            throw new AssertionError("could not set up the plans household: " + setup.body());
        }
        ApiClient owner = signIn(OWNER_EMAIL, OWNER_SECRET);
        acceptInvitation(invite(owner, MEMBER_EMAIL, "MEMBER"), "Grace", MEMBER_SECRET);
        acceptInvitation(invite(owner, VIEWER_EMAIL, "VIEWER"), "Alan", VIEWER_SECRET);
        return new Household(
                owner, signIn(MEMBER_EMAIL, MEMBER_SECRET), signIn(VIEWER_EMAIL, VIEWER_SECRET));
    }

    /** Two phones' tokens, issued while the clock is at real time (see the class notes). */
    private Phones issuePhones() {
        String ownerToken = issueToken(OWNER_EMAIL, OWNER_SECRET, DemoFixtureIT.PHONE_LABEL);
        String viewerToken = issueToken(VIEWER_EMAIL, VIEWER_SECRET, DemoFixtureIT.PHONE_LABEL);
        return new Phones(
                new Caller("owner", anonymous().withBearerToken(ownerToken), Map.of()),
                new Caller("viewer", anonymous().withBearerToken(viewerToken), Map.of()),
                ownerToken,
                viewerToken);
    }

    /**
     * Bills on every schedule, a fortnightly wage, the designs' debts and goals.
     *
     * <ul>
     *   <li><b>Bills</b>, in August 2026 with today the 20th: rent paid on the 1st; the phone paid
     *       at more than budgeted; electricity, whose amount varies, overdue since the 18th; the
     *       internet due on the 25th and mapped to a charge by a check-in; a weekly gym paid, then
     *       skipped, then overdue, then due twice; the car payment, which is the car loan's
     *       minimum, paid; a yearly insurance not due this month; an irregular window cleaner; and
     *       a retired streaming subscription.
     *   <li><b>Income</b>: a fortnightly salary that lands twice in August, a monthly benefit, and
     *       a retired freelance income.
     *   <li><b>Debts</b>: the designs' Visa, car loan and student loan, the Visa paid down from
     *       February and confirmed against August's statement, on an avalanche plan of $905.
     *   <li><b>Goals</b>: Japan with a step up from September, an emergency fund kept in savings
     *       with a withdrawal, a laptop already reached, and an archived wedding gift.
     * </ul>
     */
    private Plans seedThePlans(Household household) {
        ApiClient owner = household.owner();
        Map<String, UUID> ids = new LinkedHashMap<>();
        Map<UUID, String> stable = new LinkedHashMap<>();
        java.util.function.BiConsumer<String, UUID> name =
                (label, id) -> {
                    ids.put(label, id);
                    stable.put(id, label);
                };

        for (Map<String, Object> category :
                jdbc.queryForList("SELECT id, name FROM categories ORDER BY name")) {
            stable.put(
                    (UUID) category.get("id"),
                    "cat-"
                            + ((String) category.get("name"))
                                    .toLowerCase(Locale.ROOT)
                                    .replaceAll("[^a-z0-9]+", "-"));
        }
        UUID checking = account(owner, "Everyday Checking", "4417", "CHECKING", "2400.00");
        UUID savings = account(owner, "Emergency Fund", null, "SAVINGS", "0.00");
        name.accept("acc-0001", checking);
        name.accept("acc-0002", savings);

        UUID rent =
                bill(
                        owner,
                        "Rent",
                        "1450.00",
                        "{\"kind\":\"MONTHLY\",\"dayOfMonth\":1}",
                        ",\"categoryId\":\"" + seededCategoryId("Rent or mortgage") + "\"");
        UUID phone = bill(owner, "Phone", "45.00", "{\"kind\":\"MONTHLY\",\"dayOfMonth\":12}", "");
        UUID power =
                bill(
                        owner,
                        "Electricity",
                        "120.00",
                        "{\"kind\":\"MONTHLY\",\"dayOfMonth\":18}",
                        ",\"amountVaries\":true,\"tolerance\":\"22.00\",\"categoryId\":\""
                                + seededCategoryId("Utilities")
                                + "\"");
        UUID internet =
                bill(owner, "Internet", "60.00", "{\"kind\":\"MONTHLY\",\"dayOfMonth\":25}", "");
        UUID gym =
                bill(owner, "Gym", "12.00", "{\"kind\":\"WEEKLY\",\"dayOfWeek\":\"MONDAY\"}", "");
        UUID car =
                bill(owner, "Car payment", "342.00", "{\"kind\":\"MONTHLY\",\"dayOfMonth\":5}", "");
        UUID insurance =
                bill(
                        owner,
                        "Car insurance",
                        "480.00",
                        "{\"kind\":\"YEARLY\",\"month\":3,\"dayOfMonth\":1}",
                        "");
        UUID cleaner = bill(owner, "Window cleaner", "25.00", "{\"kind\":\"IRREGULAR\"}", "");
        UUID streaming =
                bill(owner, "Streaming", "15.99", "{\"kind\":\"MONTHLY\",\"dayOfMonth\":9}", "");
        name.accept("bil-0001", rent);
        name.accept("bil-0002", phone);
        name.accept("bil-0003", power);
        name.accept("bil-0004", internet);
        name.accept("bil-0005", gym);
        name.accept("bil-0006", car);
        name.accept("bil-0007", insurance);
        name.accept("bil-0008", cleaner);
        name.accept("bil-0009", streaming);
        record(owner, rent, "2026-08-01", "{\"status\":\"PAID\",\"paidDate\":\"2026-08-01\"}");
        record(
                owner,
                phone,
                "2026-08-12",
                "{\"status\":\"PAID\",\"actualAmount\":\"52.30\",\"paidDate\":\"2026-08-12\"}");
        record(owner, gym, "2026-08-03", "{\"status\":\"PAID\",\"paidDate\":\"2026-08-03\"}");
        record(owner, gym, "2026-08-10", "{\"status\":\"SKIPPED\"}");
        record(owner, car, "2026-08-05", "{\"status\":\"PAID\",\"paidDate\":\"2026-08-05\"}");
        retire(owner, "/api/bills/" + streaming + "?period=" + PERIOD, "/api/bills/" + streaming);

        UUID salary =
                income(
                        owner,
                        "Salary",
                        "1850.00",
                        "{\"kind\":\"FORTNIGHTLY\",\"dayOfWeek\":\"FRIDAY\",\"anchorDate\":\"2026-07-31\"}",
                        ",\"accountId\":\"" + checking + "\"");
        UUID benefit =
                income(
                        owner,
                        "Child benefit",
                        "102.40",
                        "{\"kind\":\"MONTHLY\",\"dayOfMonth\":7}",
                        "");
        UUID freelance =
                income(
                        owner,
                        "Freelance",
                        "300.00",
                        "{\"kind\":\"MONTHLY\",\"dayOfMonth\":15}",
                        "");
        name.accept("inc-0001", salary);
        name.accept("inc-0002", benefit);
        name.accept("inc-0003", freelance);
        retire(owner, "/api/income/" + freelance + "?period=" + PERIOD, "/api/income/" + freelance);

        // The internet's mapping. Charge rules are written only by completing a check-in
        // (ADR-0041) and there is no route that creates one, so this one row is put in directly,
        // against a payee the API made; what the fixture records is the API reading it back.
        created(
                owner.post(
                        "/api/transactions",
                        """
                {"accountId":"%s","date":"2026-07-25","amount":"-60.00","status":"CLEARED",
                 "payeeName":"COMCAST XFINITY"}
                """
                                .formatted(checking)));
        UUID payee = jdbc.queryForObject("SELECT id FROM payees", UUID.class);
        UUID householdId = jdbc.queryForObject("SELECT id FROM households", UUID.class);
        UUID rule = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO charge_rules (id, household_id, bill_id, payee_id, account_id)"
                        + " VALUES (?, ?, ?, ?, ?)",
                rule,
                householdId,
                internet,
                payee,
                checking);
        name.accept("rul-0001", rule);
        stable.put(payee, "payee-comcast-xfinity");
        for (Map<String, Object> row : jdbc.queryForList("SELECT id FROM transactions")) {
            stable.put((UUID) row.get("id"), "row-2026-07-25-acc-0001-out-60");
        }

        seedTheDebts(owner, car, name);
        seedTheGoals(owner, savings, name);
        return new Plans(ids, stable);
    }

    private void seedTheDebts(
            ApiClient owner, UUID carPayment, java.util.function.BiConsumer<String, UUID> name) {
        UUID visa =
                debt(
                        owner,
                        "Visa",
                        "CREDIT_CARD",
                        "Chase",
                        "8240.00",
                        "22.900",
                        "185.00",
                        "\"10650.00\"",
                        "2026-02-20",
                        null);
        for (int month = 3; month <= 8; month++) {
            created(
                    owner.post(
                            "/api/debts/" + visa + "/payments",
                            "{\"paidDate\":\"2026-%02d-20\",\"amount\":\"445.00\"}"
                                    .formatted(month)));
        }
        ok(
                owner.post(
                        "/api/debts/" + visa + "/balance",
                        "{\"balance\":\"6180.00\",\"balanceDate\":\""
                                + TODAY
                                + "\",\"note\":\"Statement from Chase\"}"));
        UUID carLoan =
                debt(
                        owner,
                        "Car loan",
                        "INSTALLMENT",
                        "Ally",
                        "10650.00",
                        "6.400",
                        "342.00",
                        "\"18400.00\"",
                        TODAY,
                        carPayment);
        UUID student =
                debt(
                        owner,
                        "Student loan",
                        "INSTALLMENT",
                        "Nelnet",
                        "9650.00",
                        "4.500",
                        "118.00",
                        "null",
                        TODAY,
                        null);
        name.accept("deb-0001", visa);
        name.accept("deb-0002", carLoan);
        name.accept("deb-0003", student);
        clock.moveTo(clock.instant().plusSeconds(1));
        ok(
                owner.put(
                        PLAN,
                        "{\"strategy\":\"AVALANCHE\",\"monthlyAmount\":\"905.00\",\"version\":"
                                + planVersion(owner)
                                + "}"));
    }

    private void seedTheGoals(
            ApiClient owner, UUID savings, java.util.function.BiConsumer<String, UUID> name) {
        UUID japan =
                goal(
                        owner,
                        "{\"name\":\"Japan\",\"targetAmount\":\"3500.00\",\"targetMonth\":\"2027-11\","
                                + "\"monthlyContribution\":\"110.00\"}");
        String[][] japanMonths = {
            {"2026-01-15", "120.00"}, {"2026-02-15", "120.00"}, {"2026-03-15", "130.00"},
            {"2026-04-15", "130.00"}, {"2026-05-15", "130.00"}, {"2026-06-15", "130.00"},
            {"2026-07-15", "130.00"}
        };
        for (String[] month : japanMonths) {
            contribute(owner, japan, month[0], month[1], null);
        }
        ok(
                owner.put(
                        "/api/goals/" + japan + "/contribution-plan/2026-09",
                        "{\"monthlyAmount\":\"165.00\",\"version\":"
                                + version(owner, "/api/goals/" + japan)
                                + "}"));
        UUID fund =
                goal(
                        owner,
                        "{\"name\":\"Emergency fund\",\"targetAmount\":\"6000.00\",\"startingAmount\":"
                                + "\"2000.00\",\"monthlyContribution\":\"200.00\",\"accountId\":\""
                                + savings
                                + "\"}");
        // A year of weekly round-ups first: more history than one page, so "Show more" has a
        // second page to read.
        for (int week = 0; week < 52; week++) {
            contribute(
                    owner,
                    fund,
                    LocalDate.parse("2025-06-01").plusWeeks(week).toString(),
                    "25.00",
                    null);
        }
        contribute(owner, fund, "2026-06-01", "200.00", null);
        contribute(owner, fund, "2026-07-01", "200.00", null);
        contribute(owner, fund, "2026-07-18", "-160.00", "Vet bill");
        contribute(owner, fund, "2026-08-03", "100.00", null);
        UUID laptop =
                goal(
                        owner,
                        "{\"name\":\"New laptop\",\"targetAmount\":\"1200.00\",\"targetMonth\":\"2026-10\"}");
        for (int month = 3; month <= 7; month++) {
            contribute(owner, laptop, "2026-%02d-10".formatted(month), "250.00", null);
        }
        UUID gift =
                goal(
                        owner,
                        "{\"name\":\"Wedding gift\",\"targetAmount\":\"300.00\",\"startingAmount\":\"300.00\"}");
        ok(owner.post("/api/goals/" + gift + "/archive", "{}"));
        name.accept("gol-0001", japan);
        name.accept("gol-0002", fund);
        name.accept("gol-0003", laptop);
        name.accept("gol-0004", gift);
    }

    // ============================================================================== helpers

    /**
     * What the phone reads on arrival, whatever tab it is on: the Transactions tab is built first.
     */
    private static void readHome(MobileRecorder record, Caller who) {
        record.send(who, "GET", "/api/auth/me", null);
        record.send(who, "GET", DemoFixtureIT.ACCOUNTS, null);
        record.send(who, "GET", DemoFixtureIT.CATEGORIES, null);
        record.send(who, "GET", DemoFixtureIT.LEDGER, null);
    }

    private static void write(MobileRecorder record, Plans plans, String area) throws IOException {
        DemoFixtureIT.compareOrWriteMobile(
                DemoFixtureIT.canonicaliseMobile(
                        record.render("plans household — " + area), plans.stable()),
                DemoFixtureIT.MOBILE_FIXTURES.resolve(area + ".generated.json"),
                Path.of("target", "mobile-" + area + ".generated.json"));
    }

    private static long version(Caller who, String path) {
        return version(who.client(), path);
    }

    /** The top-level version: a bill's occurrences carry versions of their own. */
    private static long version(ApiClient who, String path) {
        ApiResponse read = who.get(path);
        if (read.status() != 200) {
            throw new AssertionError("could not read " + path + ": " + read.body());
        }
        return JSON.readTree(read.body()).get("version").asLong();
    }

    /** A debt's id, from the detail every debt route returns. */
    private static UUID debtId(ApiResponse detail) {
        return UUID.fromString(JSON.readTree(detail.body()).get("debt").get("id").asString());
    }

    private UUID account(
            ApiClient owner, String name, String lastFour, String type, String openingBalance) {
        return id(
                created(
                        owner.post(
                                "/api/accounts",
                                """
                {"name":"%s","lastFour":%s,"type":"%s","currency":"USD","openingBalance":"%s"}
                """
                                        .formatted(
                                                name,
                                                lastFour == null ? "null" : "\"" + lastFour + "\"",
                                                type,
                                                openingBalance))));
    }

    private static UUID bill(
            ApiClient owner, String name, String amount, String schedule, String extra) {
        return id(
                created(
                        owner.post(
                                "/api/bills",
                                "{\"name\":\"%s\",\"expectedAmount\":\"%s\",\"schedule\":%s%s}"
                                        .formatted(name, amount, schedule, extra))));
    }

    private static UUID income(
            ApiClient owner, String name, String amount, String schedule, String extra) {
        return id(
                created(
                        owner.post(
                                "/api/income",
                                "{\"name\":\"%s\",\"amount\":\"%s\",\"schedule\":%s%s}"
                                        .formatted(name, amount, schedule, extra))));
    }

    private static void record(ApiClient owner, UUID bill, String date, String body) {
        ok(owner.put("/api/bills/" + bill + "/occurrences/" + date, body));
    }

    private static void retire(ApiClient owner, String read, String write) {
        ok(owner.patch(write, "{\"version\":" + version(owner, read) + ",\"active\":false}"));
    }

    private static UUID debt(
            ApiClient owner,
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
                created(
                        owner.post(
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
                                                originalBalance,
                                                billId == null ? "null" : "\"" + billId + "\"")));
        return debtId(created);
    }

    private static UUID goal(ApiClient owner, String body) {
        return id(created(owner.post("/api/goals", body)));
    }

    private static void contribute(
            ApiClient owner, UUID goal, String date, String amount, String note) {
        created(
                owner.post(
                        "/api/goals/" + goal + "/contributions",
                        "{\"amount\":\"%s\",\"date\":\"%s\"%s}"
                                .formatted(
                                        amount,
                                        date,
                                        note == null ? "" : ",\"note\":\"" + note + "\"")));
    }

    /** The top-level id. A bill's occurrences and mappings carry ids of their own. */
    private static UUID id(ApiResponse response) {
        return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
    }

    private static ApiResponse created(ApiResponse response) {
        if (response.status() != 201) {
            throw new AssertionError("a plans household step failed: " + response.body());
        }
        return response;
    }

    private static ApiResponse ok(ApiResponse response) {
        if (response.status() != 200) {
            throw new AssertionError("a plans household step failed: " + response.body());
        }
        return response;
    }
}
