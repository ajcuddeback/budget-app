package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Flutter app's fixtures for <b>the weekly check-in, Insights and import</b> (slice 15, phase
 * 2), from the real application, as {@code DemoFixtureIT} writes the phone's other fixtures.
 *
 * <p>Each method tells one story through the real API, in order, because each write moves the
 * server: the recorded exchange carries the state it was made in and, for a write, the state it
 * left the server in. The app's fake ({@code mobile/test/support/fixture_server.dart}) answers only
 * these exchanges and fails a test on anything else, so a screen cannot show a figure the
 * application did not compute.
 *
 * <p>Kept apart from {@code DemoFixtureIT} so phase 2's areas can each record their own file
 * without editing one shared method. It borrows that class's demo ledger and its clock bean.
 *
 * <p><b>Regenerating.</b> {@code -Dfixtures.write=true}, then read the diff: a change here changes
 * the goldens drawn from it, and every one of them has to be opened and read again.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@Import(DemoFixtureIT.DemoClock.class)
class MobileWeeklyFixtureIT extends ApiTestBase {

    private static final Path MOBILE_FIXTURES = Path.of("..", "mobile", "test", "fixtures");

    /** The demo household's "now" (DemoFixtureIT.DEMO_TODAY), and the phone's in its tests. */
    private static final Instant DEMO_TODAY = Instant.parse("2026-08-20T12:00:00Z");

    /** The Monday after the canvas week: week 35, with week 34's draft still open. */
    private static final Instant WEEK_35_MONDAY = Instant.parse("2026-08-24T12:00:00Z");

    private static final String LEDGER = "/api/transactions?page=0&size=50";
    private static final String ACCOUNTS = "/api/accounts?includeArchived=true&page=0&size=200";
    private static final String CATEGORIES = "/api/categories?includeArchived=true&page=0&size=200";
    private static final String CURRENT = "/api/check-ins/current";
    private static final String IMPORTS = "/api/imports?page=0&size=10";
    private static final String JSON_TYPE = "application/json";
    private static final String CSV_TYPE = "text/csv";

    /** The three ranges the Insights chips ask for on the demo day, by the phone's own date. */
    private static final List<String> RANGES =
            List.of(
                    "from=2025-09&to=2026-08",
                    "from=2026-03&to=2026-08",
                    "from=2026-06&to=2026-08");

    /** "Where {month} went": July by default (the last complete month), a step either way. */
    private static final List<String> MONTHS = List.of("2026-06", "2026-07", "2026-08");

    @Autowired private DemoFixtureIT.SettableClock clock;

    // ====================================================================== the check-in

    /**
     * The canvas week (docs/features/weekly-check-in.md) as the phone walks it: not started, a
     * draft started and discarded, started again, each step answered, another member's answer
     * winning a race, the next Monday finding this draft still open, a ledger edit refusing the
     * finish, the re-answer, and the finished week — read by the owner and by a viewer.
     */
    @Test
    void the_committed_mobile_check_in_fixtures_are_what_the_application_returns()
            throws IOException {
        clock.moveTo(Instant.now());
        Household household = seedUsdHousehold();
        Recorder record = new Recorder();
        // Issued at real time: a token "issued" in August 2026 would expire before the database
        // says it was created (ck_auth_tokens_expires_after_creation).
        Caller owner = phone(record, OWNER_EMAIL, OWNER_SECRET, "owner");
        Caller viewer = phone(record, VIEWER_EMAIL, VIEWER_SECRET, "viewer");
        Map<String, UUID> ids =
                new CanvasWeek(household.owner(), jdbc, clock::moveTo, "USD").build();
        // The clock is now the canvas's Friday, 21 August: week 34.
        Map<UUID, String> stable = new LinkedHashMap<>();
        Caller stranger = new Caller(null, anonymous());

        record.in("*");
        record.send(stranger, "GET", "/api/meta", null);

        record.in("not-started");
        readCheckIn(record, owner);
        record.in("viewer-not-started");
        readCheckIn(record, viewer);
        // A viewer's start, if one is ever sent: the API's 403, before the week is looked up.
        record.send(viewer, "PUT", CanvasWeek.W34, null);

        record.in("not-started");
        record.sendThen("draft", owner, "PUT", CanvasWeek.W34, null);
        record.in("draft");
        readCheckIn(record, owner);
        record.sendThen("discarded", owner, "DELETE", CanvasWeek.W34, null);
        record.in("discarded");
        readCheckIn(record, owner);
        record.sendThen("started", owner, "PUT", CanvasWeek.W34, null);
        record.in("started");
        JsonNode week = readCheckIn(record, owner);
        nameEverything(stable);

        // Step 1: the gym's three months at once, prepaid.
        answer(record, owner, "started", "prepaid", "odd." + ids.get("gymCharge"), "{\"choice\":\"PREPAID\"}");
        // Step 2: three categories, and "always file" ticked on the first.
        String blue = "cat." + ids.get("blueBottle");
        answer(
                record,
                owner,
                "prepaid",
                "filed-blue",
                blue,
                category(seededCategoryId("Eating Out"), false, null));
        week = CanvasWeek.json(household.owner().get(CanvasWeek.W34));
        answer(
                record,
                owner,
                "filed-blue",
                "remembered",
                blue,
                category(seededCategoryId("Eating Out"), true, answerVersion(week, blue)));
        String whole = "cat." + ids.get("wholeFoods");
        answer(
                record,
                owner,
                "remembered",
                "filed-whole",
                whole,
                category(seededCategoryId("Groceries"), false, null));
        answer(
                record,
                owner,
                "filed-whole",
                "filed-ride",
                "cat." + ids.get("ride"),
                category(seededCategoryId("Transport"), false, null));
        // Step 3: the repeating Comcast charge, mapped to the Internet bill the server suggests.
        week = CanvasWeek.json(household.owner().get(CanvasWeek.W34));
        String comcast = "chg." + ids.get("comcast");
        JsonNode charge = item(week, comcast);
        answer(
                record,
                owner,
                "filed-ride",
                "mapped",
                comcast,
                """
                {"choice":"ATTACH","billId":"%s","dueDate":"%s","remember":true,"recordDebtPayment":false}
                """
                        .formatted(
                                charge.get("suggestedBillId").asString(),
                                charge.get("suggestedDueDate").asString()));

        // Step 4. Another member rolls the surplus over first; the phone, still holding the
        // unanswered step, sends its own and gets 409 stale-version, then re-reads Grace's.
        week = CanvasWeek.json(household.owner().get(CanvasWeek.W34));
        String surplus = surplus(week);
        String rollOver = "{\"choice\":\"ROLL_OVER\",\"amount\":\"%s\"}".formatted(surplus);
        CanvasWeek.ok(household.member().put(CanvasWeek.W34 + "/answers/alloc", rollOver), 200);
        record.send(owner, "PUT", CanvasWeek.W34 + "/answers/alloc", rollOver);
        record.in("mapped");
        // The fake moves on from the refusal to the state Grace's answer made.
        record.retarget("rolled");
        record.in("rolled");
        week = readCheckIn(record, owner);
        answer(
                record,
                owner,
                "rolled",
                "allocated",
                "alloc",
                """
                {"choice":"GOAL","goalId":"%s","amount":"%s","version":%d}
                """
                        .formatted(ids.get("fund"), surplus, answerVersion(week, "alloc")));
        week = CanvasWeek.json(household.owner().get(CanvasWeek.W34));

        // A viewer reads the whole draft, and a forced answer is the API's 403.
        record.in("viewer-draft");
        readCheckIn(record, viewer);
        record.send(viewer, "PUT", CanvasWeek.W34 + "/answers/alloc", rollOver);

        // The next Monday: week 35 cannot start while week 34's draft is open.
        clock.moveTo(WEEK_35_MONDAY);
        record.in("next-week");
        readCheckIn(record, owner);
        record.send(owner, "PUT", "/api/check-ins/2026-W35", null);
        record.send(owner, "GET", CanvasWeek.W34, null);
        clock.moveTo(CanvasWeek.WEEK_34_FRIDAY);

        // Somebody edits an answered row in the ledger, so finishing with what the phone saw is
        // refused as a whole, with the item named, and nothing is written.
        UUID wholeFoods = ids.get("wholeFoods");
        long rowVersion =
                jdbc.queryForObject(
                        "SELECT version FROM transactions WHERE id = ?", Long.class, wholeFoods);
        CanvasWeek.ok(
                household
                        .owner()
                        .patch(
                                "/api/transactions/" + wholeFoods,
                                "{\"version\":%d,\"note\":\"weekly shop\"}".formatted(rowVersion)),
                200);
        record.in("allocated");
        record.sendThen("stale", owner, "POST", CanvasWeek.W34 + "/complete", completion(week));
        record.in("stale");
        week = readCheckIn(record, owner);
        answer(
                record,
                owner,
                "stale",
                "reanswered",
                whole,
                category(seededCategoryId("Groceries"), false, answerVersion(week, whole)));
        week = CanvasWeek.json(household.owner().get(CanvasWeek.W34));
        record.in("reanswered");
        ApiResponse done =
                record.sendThen(
                        "completed", owner, "POST", CanvasWeek.W34 + "/complete", completion(week));
        if (done.status() != 200) {
            throw new AssertionError("could not complete the phone's week: " + done.body());
        }
        record.in("completed");
        readCheckIn(record, owner);
        record.in("viewer-completed");
        readCheckIn(record, viewer);

        nameEverything(stable);
        write(record, "check-in", "the canvas check-in week", stable);
    }

    private static JsonNode readCheckIn(Recorder record, Caller who) {
        readTheApp(record, who);
        return CanvasWeek.json(record.send(who, "GET", CURRENT, null));
    }

    private static void answer(
            Recorder record,
            Caller owner,
            String from,
            String to,
            String itemKey,
            String body) {
        record.in(from);
        ApiResponse answered =
                record.sendThen(to, owner, "PUT", CanvasWeek.W34 + "/answers/" + itemKey, body);
        if (answered.status() != 200) {
            throw new AssertionError("could not answer " + itemKey + ": " + answered.body());
        }
        record.in(to);
        readCheckIn(record, owner);
    }

    /** A category answer, exactly as the app builds it: the version only when it replaces one. */
    private static String category(UUID categoryId, boolean remember, Long version) {
        return """
               {"choice":"CATEGORY","categoryId":"%s","remember":%s%s}
               """
                .formatted(categoryId, remember, version == null ? "" : ",\"version\":" + version);
    }

    private static String completion(JsonNode week) {
        return "{\"version\":%d,\"basis\":\"%s\"}"
                .formatted(week.get("version").asLong(), week.get("basis").asString());
    }

    private static JsonNode item(JsonNode week, String itemKey) {
        for (JsonNode step : week.get("steps")) {
            for (JsonNode item : step.get("items")) {
                if (item.get("itemKey").asString().equals(itemKey)) {
                    return item;
                }
            }
        }
        throw new AssertionError("no item " + itemKey + " in the week");
    }

    private static long answerVersion(JsonNode week, String itemKey) {
        JsonNode answer = item(week, itemKey).get("answer");
        if (answer == null || answer.isNull()) {
            throw new AssertionError(itemKey + " has no answer to replace");
        }
        return answer.get("version").asLong();
    }

    /** The server's own surplus, sent back as the app sends it: never typed, never worked out. */
    private static String surplus(JsonNode week) {
        for (JsonNode step : week.get("steps")) {
            if ("NEXT_WEEK".equals(step.get("step").asString())) {
                return step.get("figures").get("surplus").get("amount").asString();
            }
        }
        throw new AssertionError("the week has no surplus");
    }

    // ========================================================================== Insights

    /**
     * The demo household's twelve months, as Insights reads them on 20 August 2026: every range a
     * chip asks for and every month "where it went" steps to, each as JSON and as the CSV the
     * share sheet sends — for the owner, and identically for a viewer.
     */
    @Test
    void the_committed_mobile_insights_fixtures_are_what_the_application_returns()
            throws IOException {
        clock.moveTo(Instant.now());
        Household household = seedUsdHousehold();
        Recorder record = new Recorder();
        Caller owner = phone(record, OWNER_EMAIL, OWNER_SECRET, "owner");
        Caller viewer = phone(record, VIEWER_EMAIL, VIEWER_SECRET, "viewer");
        Map<UUID, String> stable = seedTheDemoLedger(household.owner());
        clock.moveTo(DEMO_TODAY);

        record.in("*");
        record.send(new Caller(null, anonymous()), "GET", "/api/meta", null);
        record.in("demo");
        readTheApp(record, owner);
        readReports(record, owner, RANGES, MONTHS);
        record.in("viewer");
        readTheApp(record, viewer);
        readReports(record, viewer, RANGES.subList(0, 1), MONTHS.subList(1, 2));

        nameEverything(stable);
        write(record, "insights", "the demo household's twelve months", stable);
    }

    /**
     * The two Insights states the demo household cannot have, from a household of their own:
     * nothing recorded at all, and money in two currencies.
     */
    @Test
    void the_committed_mobile_insights_state_fixtures_are_what_the_application_returns()
            throws IOException {
        clock.moveTo(Instant.now());
        Household household = seedUsdHousehold();
        Recorder record = new Recorder();
        Caller owner = phone(record, OWNER_EMAIL, OWNER_SECRET, "owner");
        clock.moveTo(DEMO_TODAY);

        record.in("*");
        record.send(new Caller(null, anonymous()), "GET", "/api/meta", null);
        record.in("empty");
        readTheApp(record, owner);
        readReports(record, owner, RANGES.subList(0, 1), MONTHS.subList(1, 2));

        clock.moveTo(Instant.now());
        ApiClient web = household.owner();
        UUID dollars = account(web, "Everyday Checking", "4417", "CHECKING", "USD", "0.00");
        UUID euros = account(web, "Berlin Savings", null, "SAVINGS", "EUR", "1200.00");
        String[][] rows = {
            {"2026-06-01", "3200.00", "USD", "Salary"},
            {"2026-06-04", "-212.40", "USD", "Groceries"},
            {"2026-07-01", "3200.00", "USD", "Salary"},
            {"2026-07-03", "-198.60", "USD", "Groceries"},
            {"2026-07-11", "-61.20", "USD", "Eating Out"},
            {"2026-06-20", "450.00", "EUR", "Salary"},
            {"2026-07-08", "-84.30", "EUR", "Groceries"},
            {"2026-07-20", "450.00", "EUR", "Salary"},
            {"2026-07-22", "-37.50", "EUR", "Eating Out"},
        };
        for (String[] row : rows) {
            createTransaction(
                    web,
                    "USD".equals(row[2]) ? dollars : euros,
                    row[0],
                    row[1],
                    seededCategoryId(row[3]));
        }
        clock.moveTo(DEMO_TODAY);
        record.in("two-currencies");
        readTheApp(record, owner);
        readReports(record, owner, RANGES.subList(0, 1), MONTHS.subList(1, 2));

        Map<UUID, String> stable = new LinkedHashMap<>();
        nameEverything(stable);
        write(record, "insights-states", "an empty household, and one in two currencies", stable);
    }

    private static void readReports(
            Recorder record, Caller who, List<String> ranges, List<String> months) {
        for (String range : ranges) {
            String path = "/api/reports/income-and-spending?" + range;
            ok(record.accepting(who, path, JSON_TYPE));
            ok(record.accepting(who, path, CSV_TYPE));
        }
        for (String month : months) {
            String path = "/api/reports/spending-by-category?from=" + month + "&to=" + month;
            ok(record.accepting(who, path, JSON_TYPE));
            ok(record.accepting(who, path, CSV_TYPE));
        }
    }

    /** The demo ledger {@code DemoFixtureIT} reports on, entered through the API. */
    private Map<UUID, String> seedTheDemoLedger(ApiClient owner) {
        Map<String, UUID> categories = new LinkedHashMap<>();
        categories.put("cat-0002", seededCategoryId("Salary"));
        categories.put("cat-0004", seededCategoryId("Rent or mortgage"));
        categories.put("cat-0005", seededCategoryId("Utilities"));
        categories.put("cat-0006", seededCategoryId("Groceries"));
        categories.put("cat-0007", seededCategoryId("Transport"));
        categories.put("cat-0008", seededCategoryId("Eating Out"));
        ok(
                owner.patch(
                        "/api/categories/" + categories.get("cat-0004"),
                        "{\"version\":0,\"name\":\"Rent\"}"));
        categories.put("cat-0009", createCategory(owner, "Wedding", "EXPENSE", null));
        Map<String, UUID> accounts = new LinkedHashMap<>();
        accounts.put(
                "acc-0001",
                account(owner, "Everyday Checking", "4417", "CHECKING", "USD", "1641.75"));
        accounts.put(
                "acc-0002", account(owner, "Emergency Fund", null, "SAVINGS", "USD", "5620.00"));
        accounts.put(
                "acc-0003",
                account(owner, "Travel Card", "8820", "CREDIT_CARD", "USD", "-215.24"));
        for (String[] row : DemoFixtureIT.DEMO_LEDGER) {
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
            CanvasWeek.ok(created, 201);
        }
        for (String[] transfer : DemoFixtureIT.DEMO_TRANSFERS) {
            CanvasWeek.ok(
                    owner.post(
                            "/api/transfers",
                            """
                            {"fromAccountId":"%s","toAccountId":"%s","date":"%s","amount":"%s"}
                            """
                                    .formatted(
                                            accounts.get(transfer[2]),
                                            accounts.get(transfer[3]),
                                            transfer[0],
                                            transfer[1])),
                    201);
        }
        ok(owner.post("/api/categories/" + categories.get("cat-0009") + "/archive", null));
        return new LinkedHashMap<>();
    }

    // ============================================================================ import

    /** An OFX statement for Everyday Checking •4417, in dollars, one row resembling a typed one. */
    private static final String STATEMENT =
            """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <?OFX OFXHEADER="200" VERSION="211" SECURITY="NONE" OLDFILEUID="NONE" NEWFILEUID="NONE"?>
            <OFX>
              <SIGNONMSGSRSV1>
                <SONRS>
                  <STATUS><CODE>0</CODE><SEVERITY>INFO</SEVERITY></STATUS>
                  <DTSERVER>20260901120000</DTSERVER>
                  <LANGUAGE>ENG</LANGUAGE>
                </SONRS>
              </SIGNONMSGSRSV1>
              <BANKMSGSRSV1>
                <STMTTRNRS>
                  <TRNUID>1</TRNUID>
                  <STATUS><CODE>0</CODE><SEVERITY>INFO</SEVERITY></STATUS>
                  <STMTRS>
                    <CURDEF>USD</CURDEF>
                    <BANKACCTFROM><BANKID>000000000</BANKID><ACCTID>990000004417</ACCTID><ACCTTYPE>CHECKING</ACCTTYPE></BANKACCTFROM>
                    <BANKTRANLIST>
                      <DTSTART>20260801</DTSTART>
                      <DTEND>20260831</DTEND>
                      <STMTTRN><TRNTYPE>DEBIT</TRNTYPE><DTPOSTED>20260803</DTPOSTED><TRNAMT>-86.40</TRNAMT><FITID>EC-0001</FITID><NAME>GREENFIELD MARKET</NAME></STMTTRN>
                      <STMTTRN><TRNTYPE>DEBIT</TRNTYPE><DTPOSTED>20260805</DTPOSTED><TRNAMT>-4.50</TRNAMT><FITID>EC-0002</FITID><NAME>CORNER KITCHEN</NAME></STMTTRN>
                      <STMTTRN><TRNTYPE>DEBIT</TRNTYPE><DTPOSTED>20260815</DTPOSTED><TRNAMT>-128.30</TRNAMT><FITID>EC-0003</FITID><NAME>METRO POWER</NAME></STMTTRN>
                      <STMTTRN><TRNTYPE>CREDIT</TRNTYPE><DTPOSTED>20260820</DTPOSTED><TRNAMT>3200.00</TRNAMT><FITID>EC-0004</FITID><NAME>NORTHWIND SYSTEMS</NAME></STMTTRN>
                    </BANKTRANLIST>
                    <LEDGERBAL><BALAMT>4622.55</BALAMT><DTASOF>20260831</DTASOF></LEDGERBAL>
                  </STMTRS>
                </STMTTRNRS>
              </BANKMSGSRSV1>
            </OFX>
            """;

    /** A CSV export, which the server has to be told how to read. */
    private static final String CSV_STATEMENT =
            """
            Date,Description,Amount
            03/08/2026,GREENFIELD MARKET,-86.40
            14/08/2026,METRO POWER,-128.30
            20/08/2026,NORTHWIND SYSTEMS,3200.00
            """;

    /**
     * A statement imported from the phone: the history, an OFX preview with one row that looks
     * typed (included on purpose), the commit and its undo; a CSV that needs its columns mapped;
     * somebody else importing the same file between the phone's preview and its commit; and a
     * viewer, who is refused before the file is read.
     */
    @Test
    void the_committed_mobile_import_fixtures_are_what_the_application_returns()
            throws IOException {
        clock.moveTo(Instant.now());
        Household household = seedUsdHousehold();
        ApiClient web = household.owner();
        UUID checking = account(web, "Everyday Checking", "4417", "CHECKING", "USD", "1641.75");
        account(web, "Travel Card", "8820", "CREDIT_CARD", "USD", "-215.24");
        UUID old = account(web, "Old Savings", null, "SAVINGS", "USD", "0.00");
        ok(web.post("/api/accounts/" + old + "/archive", null));
        // The coffee somebody typed in on the day, which the statement then carries again.
        CanvasWeek.ok(
                web.post(
                        "/api/transactions",
                        """
                        {"accountId":"%s","date":"2026-08-04","amount":"-4.50",
                         "payeeName":"Corner Kitchen","categoryId":"%s"}
                        """
                                .formatted(checking, seededCategoryId("Eating Out"))),
                201);
        Recorder record = new Recorder();
        Caller owner = phone(record, OWNER_EMAIL, OWNER_SECRET, "owner");
        Caller viewer = phone(record, VIEWER_EMAIL, VIEWER_SECRET, "viewer");
        String ofx = fileBody(checking, "OFX", STATEMENT, null);

        record.in("*");
        record.send(new Caller(null, anonymous()), "GET", "/api/meta", null);

        record.in("viewer");
        readTheApp(record, viewer);
        // A viewer's preview, if one is ever sent: 403 before a byte of the file is decoded.
        record.send(viewer, "POST", "/api/imports/preview", ofx);

        record.in("ready");
        readImport(record, owner);
        JsonNode preview = json(record.send(owner, "POST", "/api/imports/preview", ofx));

        // A CSV: read with no settings, the saved mapping asked for (there is none), then read
        // again with the settings the server suggested, as the phone shows them.
        String csv = fileBody(checking, "CSV", CSV_STATEMENT, null);
        JsonNode csvPreview = json(record.send(owner, "POST", "/api/imports/preview", csv));
        record.send(owner, "GET", "/api/accounts/" + checking + "/import-mapping", null);
        record.send(
                owner,
                "POST",
                "/api/imports/preview",
                fileBody(checking, "CSV", CSV_STATEMENT, suggestedOptions(csvPreview)));

        // Commit the OFX with the row that looks typed ticked to be included after all.
        int suspected = -1;
        for (JsonNode attention : preview.get("attention")) {
            if ("SUSPECTED_DUPLICATE".equals(attention.get("kind").asString())) {
                suspected = attention.get("row").asInt();
            }
        }
        if (suspected < 0) {
            throw new AssertionError("the statement has no suspected duplicate: " + preview);
        }
        String commit =
                commitBody(
                        ofx,
                        preview.get("previewDigest").asString(),
                        ",\"suspectedDuplicates\":\"EXCLUDE\",\"overrides\":[{\"row\":%d,\"include\":true}]"
                                .formatted(suspected));
        ApiResponse committed =
                record.sendThen("imported", owner, "POST", "/api/imports", commit);
        CanvasWeek.ok(committed, 201);
        record.in("imported");
        readImport(record, owner);
        record.sendThen(
                "undone", owner, "DELETE", "/api/imports/" + committed.stringField("id"), null);
        record.in("undone");
        readImport(record, owner);

        // Previewed again; before the phone confirms, somebody on the web imports a shorter
        // statement carrying two of the same rows, so the phone's commit is refused and it
        // previews the same request again. (The same file twice would not do: a commit with a
        // digest already written is a retry, answered 200 with that batch.)
        JsonNode again = json(record.send(owner, "POST", "/api/imports/preview", ofx));
        String part =
                fileBody(
                        checking,
                        "OFX",
                        String.join(
                                        "\n",
                                        STATEMENT
                                                .lines()
                                                .filter(
                                                        line ->
                                                                !line.contains("EC-0002")
                                                                        && !line.contains(
                                                                                "EC-0004"))
                                                .toList())
                                + "\n",
                        null);
        JsonNode theirs = json(web.post("/api/imports/preview", part));
        CanvasWeek.ok(
                web.post(
                        "/api/imports",
                        commitBody(
                                part,
                                theirs.get("previewDigest").asString(),
                                ",\"suspectedDuplicates\":\"EXCLUDE\"")),
                201);
        record.sendThen(
                "stale",
                owner,
                "POST",
                "/api/imports",
                commitBody(
                        ofx,
                        again.get("previewDigest").asString(),
                        ",\"suspectedDuplicates\":\"EXCLUDE\""));
        record.in("stale");
        readImport(record, owner);
        record.send(owner, "POST", "/api/imports/preview", ofx);

        Map<UUID, String> stable = new LinkedHashMap<>();
        nameEverything(stable);
        write(record, "import", "a statement imported from the phone", stable);
    }

    private static void readImport(Recorder record, Caller who) {
        readTheApp(record, who);
        record.send(who, "GET", IMPORTS, null);
    }

    private static String fileBody(UUID accountId, String format, String file, String options) {
        String base64 = Base64.getEncoder().encodeToString(file.getBytes(StandardCharsets.UTF_8));
        return "{\"accountId\":\"%s\",\"format\":\"%s\",\"file\":\"%s\"%s}"
                .formatted(accountId, format, base64, options == null ? "" : ",\"options\":" + options);
    }

    private static String commitBody(String previewBody, String digest, String decisions) {
        return previewBody.substring(0, previewBody.length() - 1)
                + ",\"previewDigest\":\""
                + digest
                + "\""
                + decisions
                + "}";
    }

    /**
     * The options the phone sends after "Update preview" when nothing was changed: the server's
     * suggestion, as {@code import_data.dart}'s {@code optionsFor} builds it for a mapped CSV.
     */
    private static String suggestedOptions(JsonNode preview) {
        JsonNode suggested = preview.get("csv").get("suggested");
        ObjectNode options = Recorder.JSON.createObjectNode();
        JsonNode dateFormat = suggested.get("dateFormat");
        if (dateFormat != null && !dateFormat.isNull() && !dateFormat.asString().isEmpty()) {
            options.put("dateFormat", dateFormat.asString());
        }
        options.put("delimiter", suggested.get("delimiter").asString());
        options.put("headerRow", suggested.get("headerRow").asInt());
        options.put("decimalSeparator", suggested.get("decimalSeparator").asString());
        JsonNode grouping = suggested.get("groupingSeparator");
        if (grouping != null && !grouping.isNull() && !grouping.asString().isEmpty()) {
            options.put("groupingSeparator", grouping.asString());
        }
        ObjectNode columns = options.putObject("columns");
        new TreeMap<>(asMap(suggested.get("columns"))).forEach(columns::put);
        return Recorder.JSON.writeValueAsString(options);
    }

    private static Map<String, String> asMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            out.put(field.getKey(), field.getValue().asString());
        }
        return out;
    }

    // ============================================================================ shared

    /** What every signed-in phone reads on arrival: who it is, and the ledger tab beneath. */
    private static void readTheApp(Recorder record, Caller who) {
        record.send(who, "GET", "/api/auth/me", null);
        record.send(who, "GET", ACCOUNTS, null);
        record.send(who, "GET", CATEGORIES, null);
        record.send(who, "GET", LEDGER, null);
    }

    /** The demo household in USD, with an owner, a member and a viewer. */
    private Household seedUsdHousehold() {
        CanvasWeek.ok(
                browser()
                        .post(
                                "/api/setup/first-user",
                                """
                                {"email":"%s","displayName":"Ada","password":"%s",
                                 "householdName":"Rivera Household","baseCurrency":"USD"}
                                """
                                        .formatted(OWNER_EMAIL, OWNER_SECRET)),
                201);
        ApiClient owner = signIn(OWNER_EMAIL, OWNER_SECRET);
        acceptInvitation(invite(owner, MEMBER_EMAIL, "MEMBER"), "Grace", MEMBER_SECRET);
        acceptInvitation(invite(owner, VIEWER_EMAIL, "VIEWER"), "Alan", VIEWER_SECRET);
        return new Household(
                owner, signIn(MEMBER_EMAIL, MEMBER_SECRET), signIn(VIEWER_EMAIL, VIEWER_SECRET));
    }

    /** A phone's bearer token, issued without being recorded, and named in the file. */
    private Caller phone(Recorder record, String email, String secret, String who) {
        String token = issueToken(email, secret, "Phone · Android");
        record.secret(token, "fixture-token-" + who);
        return new Caller(who, anonymous().withBearerToken(token));
    }

    private static UUID account(
            ApiClient owner,
            String name,
            String lastFour,
            String type,
            String currency,
            String openingBalance) {
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
        return UUID.fromString(CanvasWeek.ok(created, 201).stringField("id"));
    }

    private static void ok(ApiResponse response) {
        CanvasWeek.ok(response, 200);
    }

    private static JsonNode json(ApiResponse response) {
        return Recorder.JSON.readTree(response.body());
    }

    /**
     * Gives every row this test made a name by what it is, before ids are canonicalised.
     *
     * <p>Numbering ids by first appearance is not enough here: the ledger orders same-date rows by
     * their random ids, so the first appearance changes from run to run and every name after it
     * would shift. A row named by its date, account and amount keeps its name however it sorts.
     */
    private void nameEverything(Map<UUID, String> stable) {
        Set<String> used = new HashSet<>(stable.values());
        nameBy(stable, used, "SELECT id, name FROM accounts ORDER BY name, id", "acc-");
        nameBy(stable, used, "SELECT id, name FROM categories ORDER BY name, id", "cat-");
        nameBy(stable, used, "SELECT id, name FROM payees ORDER BY name, id", "payee-");
        nameBy(stable, used, "SELECT id, name FROM bills ORDER BY name, id", "bill-");
        nameBy(stable, used, "SELECT id, name FROM goals ORDER BY name, id", "goal-");
        nameBy(stable, used, "SELECT id, name FROM debts ORDER BY name, id", "debt-");
        nameBy(stable, used, "SELECT id, name FROM envelopes ORDER BY name, id", "env-");
        nameBy(stable, used, "SELECT id, name FROM income_sources ORDER BY name, id", "income-");
        nameBy(stable, used, "SELECT id, display_name AS name FROM users ORDER BY 2, id", "user-");
        for (Map<String, Object> row :
                jdbc.queryForList(
                        "SELECT t.id, t.date, a.name AS account, t.amount FROM transactions t"
                                + " JOIN accounts a ON a.id = t.account_id ORDER BY t.date, t.id")) {
            UUID id = (UUID) row.get("id");
            if (stable.containsKey(id)) {
                continue;
            }
            java.math.BigDecimal amount = (java.math.BigDecimal) row.get("amount");
            String base =
                    "row-"
                            + row.get("date")
                            + "-"
                            + slug((String) row.get("account"))
                            + (amount.signum() < 0 ? "-out-" : "-in-")
                            + amount.abs().stripTrailingZeros().toPlainString();
            unique(stable, used, id, base);
        }
    }

    private void nameBy(Map<UUID, String> stable, Set<String> used, String sql, String prefix) {
        for (Map<String, Object> row : jdbc.queryForList(sql)) {
            UUID id = (UUID) row.get("id");
            if (!stable.containsKey(id)) {
                unique(stable, used, id, prefix + slug((String) row.get("name")));
            }
        }
    }

    private static void unique(Map<UUID, String> stable, Set<String> used, UUID id, String base) {
        String name = base;
        for (int n = 2; used.contains(name); n++) {
            name = base + "-" + n;
        }
        used.add(name);
        stable.put(id, name);
    }

    private static String slug(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return slug.replaceAll("(^-+|-+$)", "");
    }

    private static void write(Recorder record, String area, String story, Map<UUID, String> stable)
            throws IOException {
        String document = canonicalise(record.render(story), stable);
        Path committed = MOBILE_FIXTURES.resolve(area + ".generated.json");
        Path regenerated = Path.of("target", "mobile-" + area + ".generated.json");
        if (Files.exists(committed)) {
            JsonNode now = orderFree(Recorder.JSON.readTree(document));
            JsonNode then = orderFree(Recorder.JSON.readTree(Files.readString(committed)));
            if (now.equals(then)) {
                return;
            }
        }
        if (Boolean.getBoolean("fixtures.write")) {
            Files.createDirectories(committed.getParent());
            Files.writeString(committed, document);
            return;
        }
        Files.createDirectories(regenerated.getParent());
        Files.writeString(regenerated, document);
        assertThat(Files.exists(committed))
                .as(
                        "These fixtures have never been generated. What this run would have"
                                + " committed is in backend/"
                                + regenerated
                                + "; re-run with -Dfixtures.write=true.")
                .isTrue();
        assertThat(document)
                .as(
                        "The phone's "
                                + area
                                + " fixtures no longer match what the application returns, so its"
                                + " goldens show figures this code does not compute. What this run"
                                + " produced is in backend/"
                                + regenerated
                                + ". Read the diff, re-run with -Dfixtures.write=true, and read"
                                + " every golden it changes.")
                .isEqualTo(Files.readString(committed));
    }

    /**
     * The document with every list of dated rows in one order, for comparing two runs.
     *
     * <p>The ledger and the check-in's items put same-date rows in the order of their random ids,
     * so the order is real and arbitrary: a regeneration may flip two of them. The committed file
     * keeps whichever order it was written with; any other change still fails.
     */
    private static JsonNode orderFree(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = Recorder.JSON.createObjectNode();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                copy.set(field.getKey(), orderFree(field.getValue()));
            }
            return copy;
        }
        if (node.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            node.forEach(item -> items.add(orderFree(item)));
            if (!items.isEmpty() && items.stream().allMatch(MobileWeeklyFixtureIT::dated)) {
                items.sort(
                        Comparator.comparing((JsonNode item) -> item.get("date").asString())
                                .thenComparing(MobileWeeklyFixtureIT::identity));
            }
            ArrayNode copy = Recorder.JSON.createArrayNode();
            items.forEach(copy::add);
            return copy;
        }
        return node;
    }

    private static boolean dated(JsonNode item) {
        return item.isObject() && item.has("date") && item.get("date").isString();
    }

    private static String identity(JsonNode item) {
        for (String key : List.of("id", "itemKey", "transactionId", "row")) {
            if (item.has(key) && !item.get(key).isNull()) {
                return item.get(key).asString();
            }
        }
        return item.toString();
    }

    /**
     * Ids to stable names (longest first, so one id is never rewritten inside another), every
     * timestamp to the demo instant, and the values a fixture cannot pin by construction: a
     * problem's correlation id, and each digest — a check-in's basis and a preview's — which is
     * taken over ids and so differs every run although nothing a person sees does.
     */
    private static String canonicalise(String document, Map<UUID, String> stable) {
        String pinned =
                document.replaceAll(
                                "\"(createdAt|updatedAt|lastUsedAt|expiresAt|joinedAt|completedAt|answeredAt|startedAt)\"(\\s*):(\\s*)\"[^\"]+\"",
                                "\"$1\"$2:$3\"" + DEMO_TODAY + "\"")
                        .replaceAll(
                                "\"correlationId\"(\\s*):(\\s*)\"[^\"]+\"",
                                "\"correlationId\"$1:$2\"fixture-correlation\"");
        Matcher digests = Pattern.compile("\\b[0-9a-f]{64}\\b").matcher(pinned);
        Map<String, String> digestNames = new LinkedHashMap<>();
        while (digests.find()) {
            digestNames.computeIfAbsent(
                    digests.group(), d -> String.format("%064d", digestNames.size() + 1));
        }
        for (Map.Entry<String, String> digest : digestNames.entrySet()) {
            pinned = pinned.replace(digest.getKey(), digest.getValue());
        }
        Map<UUID, String> names = new LinkedHashMap<>(stable);
        Matcher ids =
                Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                        .matcher(pinned);
        int next = 1;
        while (ids.find()) {
            UUID found = UUID.fromString(ids.group());
            if (!names.containsKey(found)) {
                names.put(found, String.format("gen-%04d", next++));
            }
        }
        String canonical = pinned;
        for (Map.Entry<UUID, String> name : names.entrySet()) {
            canonical = canonical.replace(name.getKey().toString(), name.getValue());
        }
        return canonical;
    }

    // ========================================================================= recorder

    /** Who made a recorded request: a token's owner by name, or nobody. */
    private record Caller(String name, ApiClient client) {}

    /**
     * Records exchanges as the phone's fake replays them — {@code DemoFixtureIT}'s recorder, plus
     * what Insights needs: a read that names its {@code Accept}, and a CSV answered as text.
     */
    private static final class Recorder {

        /** Non-ASCII escaped, so a CSV's byte-order mark is visible in the file, not invisible. */
        static final ObjectMapper JSON =
                JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();

        private final ArrayNode exchanges = JSON.createArrayNode();
        private final Map<String, String> secrets = new LinkedHashMap<>();
        private String state = "*";

        void in(String next) {
            state = next;
        }

        void secret(String raw, String canonical) {
            secrets.put(raw, canonical);
        }

        /** Points the last exchange at the state the server is in now, for a refusal that raced. */
        void retarget(String then) {
            ((ObjectNode) exchanges.get(exchanges.size() - 1)).put("then", then);
        }

        ApiResponse send(Caller caller, String method, String path, String body) {
            return exchange(caller, method, path, body, null, null);
        }

        ApiResponse sendThen(String then, Caller caller, String method, String path, String body) {
            return exchange(caller, method, path, body, null, then);
        }

        /** A {@code GET} asking for one representation, recorded with the {@code Accept} it sent. */
        ApiResponse accepting(Caller caller, String path, String accept) {
            return exchange(caller, "GET", path, null, accept, null);
        }

        private ApiResponse exchange(
                Caller caller, String method, String path, String body, String accept, String then) {
            ApiResponse response =
                    accept == null
                            ? caller.client().send(method, path, body)
                            : caller.client().getAccepting(path, accept);
            ObjectNode exchange = JSON.createObjectNode();
            exchange.put("state", state);
            exchange.put("method", method);
            exchange.put("path", path);
            if (caller.name() == null) {
                exchange.putNull("auth");
            } else {
                exchange.put("auth", caller.name());
            }
            if (accept != null) {
                exchange.putObject("requestHeaders").put("Accept", accept);
            }
            exchange.set("request", body == null ? JSON.nullNode() : JSON.readTree(body));
            exchange.put("status", response.status());
            ObjectNode headers = JSON.createObjectNode();
            response.header("Retry-After").ifPresent(value -> headers.put("Retry-After", value));
            if (path.equals("/api/meta")) {
                response.header("Cache-Control")
                        .ifPresent(value -> headers.put("Cache-Control", value));
            }
            boolean text = CSV_TYPE.equals(accept) && response.status() == 200;
            if (text) {
                response.header("Content-Type")
                        .ifPresent(value -> headers.put("Content-Type", value));
            }
            if (!headers.isEmpty()) {
                exchange.set("responseHeaders", headers);
            }
            if (text) {
                exchange.put("body", response.body());
            } else {
                exchange.set(
                        "body",
                        response.body() == null || response.body().isBlank()
                                ? JSON.nullNode()
                                : JSON.readTree(response.body()));
            }
            if (then != null) {
                exchange.put("then", then);
            }
            exchanges.add(exchange);
            return response;
        }

        String render(String story) {
            ObjectNode root = JSON.createObjectNode();
            root.put(
                    "_generated",
                    "GENERATED by backend MobileWeeklyFixtureIT ("
                            + story
                            + "). Do not edit by hand. Real responses of the real application,"
                            + " clock fixed, ids, digests and credentials canonicalised."
                            + " Regenerate: mvn -f backend verify -Dit.test='MobileWeeklyFixtureIT*'"
                            + " -Dfixtures.write=true");
            root.set("exchanges", exchanges);
            String document = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            for (Map.Entry<String, String> secret : secrets.entrySet()) {
                document = document.replace(secret.getKey(), secret.getValue());
            }
            return document + "\n";
        }
    }
}
