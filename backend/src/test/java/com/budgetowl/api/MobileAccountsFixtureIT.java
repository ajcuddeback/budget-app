package com.budgetowl.api;

import com.budgetowl.api.DemoFixtureIT.Caller;
import com.budgetowl.api.DemoFixtureIT.MobileRecorder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The Flutter app's fixtures for <b>accounts, categories (with payee rules) and envelopes</b>, from
 * the real stack — slice 15, phase 2 (docs/features/mobile-app.md).
 *
 * <p>The same contract as {@code DemoFixtureIT}'s mobile methods, whose recorder, caller and
 * canonicaliser this reuses: every exchange the phone's screens make is sent to the real
 * application and written down exactly as it answered, ids and credentials canonicalised. The
 * phone's fake server ({@code mobile/test/support/fixture_server.dart}) answers <b>only</b> these,
 * so a golden can only show a figure the application computed. Each test writes its own file, so
 * three areas can regenerate without touching anybody else's.
 *
 * <p>Each file is one story told in order, because every write moves the server: the reads a screen
 * makes are recorded in the state each write leaves the server in, and a widget test that starts in
 * any state sees exactly what the API would have shown it.
 *
 * <p><b>Regenerating.</b> {@code -Dfixtures.write=true}, then read the diff and the goldens drawn
 * from it ({@code tools/mobile-check.sh}).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class MobileAccountsFixtureIT extends ApiTestBase {

    private static final String PAYEE_RULES = "/api/payee-rules?page=0&size=200";

    private static final String PERIODS = "/api/envelopes/periods";

    /** One month's envelopes as the phone asks for them: archived included, one page of 200. */
    private static String envelopes(String period) {
        return "/api/envelopes?period=" + period + "&includeArchived=true&page=0&size=200";
    }

    private static String entries(UUID envelope) {
        return "/api/envelopes/" + envelope + "/entries?page=0&size=50";
    }

    // ================================================================================= accounts

    /**
     * Accounts in two currencies, two of them archived — one with history, one empty — and the
     * whole of the screen's writes: a refused add, an add, an edit that clears the digits, archive,
     * bring back, delete, and a {@code 409} because somebody else saved first.
     */
    @Test
    void the_committed_mobile_accounts_fixtures_are_what_the_application_returns()
            throws IOException {
        Phones phones = seedPhones();
        ApiClient owner = phones.household().owner();
        Map<UUID, String> stable = new LinkedHashMap<>();

        UUID everyday = account(owner, "Everyday Checking", "4417", "CHECKING", "USD", "1641.75");
        UUID emergency = account(owner, "Emergency Fund", null, "SAVINGS", "USD", "5620.00");
        UUID travel = account(owner, "Travel Card", "8820", "CREDIT_CARD", "USD", "-215.24");
        account(owner, "Berlin Savings", null, "SAVINGS", "EUR", "1200.00");
        UUID joint = account(owner, "Old Joint Account", null, "CHECKING", "USD", "310.00");
        UUID gift = account(owner, "Gift Card", null, "CASH", "USD", "25.00");
        spend(owner, everyday, "2026-08-01", "3200.00", "Northwind Systems", "Salary");
        spend(owner, everyday, "2026-08-03", "-86.40", "Greenfield Market", "Groceries");
        spend(owner, travel, "2026-08-09", "-41.20", "Corner Kitchen", "Eating Out");
        // Its history nets to nothing: a zero balance, and still not empty — a server fact.
        spend(owner, joint, "2026-07-15", "-310.00", "Fairview Lettings", "Rent or mortgage");
        ok(owner.post("/api/accounts/" + joint + "/archive", null), 200);
        ok(owner.post("/api/accounts/" + gift + "/archive", null), 200);
        name(stable);

        MobileRecorder record = phones.record();
        Caller you = phones.owner();
        Caller viewer = phones.viewer();
        String cashTin =
                """
                {"name":"Cash Tin","type":"CASH","currency":"USD","openingBalance":"40.00"}
                """;

        record.in("accounts");
        readAll(record, you);
        // A code the form's shape allows and the server does not know: refused, field named.
        record.send(you, "POST", "/api/accounts", cashTin.replace("USD", "ZZZ"));

        record.in("accounts-viewer");
        readAll(record, viewer);
        record.send(viewer, "POST", "/api/accounts", cashTin);

        record.in("accounts");
        record.sendThen("account-added", you, "POST", "/api/accounts", cashTin);
        name(stable);
        record.in("account-added");
        readAll(record, you);

        record.sendThen(
                "account-edited",
                you,
                "PATCH",
                "/api/accounts/" + travel,
                """
                {"version":%d,"name":"Travel Rewards","lastFour":null}
                """
                        .formatted(version(owner, "/api/accounts/" + travel)));
        record.in("account-edited");
        readAll(record, you);

        record.sendThen(
                "account-archived", you, "POST", "/api/accounts/" + emergency + "/archive", null);
        record.in("account-archived");
        readAll(record, you);

        record.sendThen(
                "account-unarchived", you, "POST", "/api/accounts/" + joint + "/unarchive", null);
        record.in("account-unarchived");
        readAll(record, you);

        record.sendThen("account-deleted", you, "DELETE", "/api/accounts/" + gift, null);
        record.in("account-deleted");
        readAll(record, you);

        // Grace renames the current account while this phone still holds version 0 of it.
        long opened = version(owner, "/api/accounts/" + everyday);
        ok(
                phones.household()
                        .member()
                        .patch(
                                "/api/accounts/" + everyday,
                                "{\"version\":%d,\"name\":\"Everyday Joint\"}".formatted(opened)),
                200);
        record.sendThen(
                "account-conflict",
                you,
                "PATCH",
                "/api/accounts/" + everyday,
                """
                {"version":%d,"openingBalance":"1700.00"}
                """
                        .formatted(opened));
        record.in("account-conflict");
        readAll(record, you);
        record.send(you, "GET", "/api/accounts/" + everyday, null);
        // "Keep mine and save": the whole form again, against the version just shown — so the
        // name goes back as well, which is what the person chose.
        record.sendThen(
                "account-overwritten",
                you,
                "PATCH",
                "/api/accounts/" + everyday,
                """
                {"version":%d,"name":"Everyday Checking","openingBalance":"1700.00"}
                """
                        .formatted(version(owner, "/api/accounts/" + everyday)));
        record.in("account-overwritten");
        readAll(record, you);

        write(record, "accounts", stable);
    }

    // =============================================================================== categories

    /**
     * The seeded defaults, an archived category, three payee rules (one on the archived category),
     * and every write the screen makes: a refused duplicate, a category, a sub-category, a rename,
     * archive, restore, delete with children, and removing a rule.
     */
    @Test
    void the_committed_mobile_categories_fixtures_are_what_the_application_returns()
            throws IOException {
        Phones phones = seedPhones();
        ApiClient owner = phones.household().owner();
        Map<UUID, String> stable = new LinkedHashMap<>();

        UUID everyday = account(owner, "Everyday Checking", "4417", "CHECKING", "USD", "1641.75");
        UUID wedding = createCategory(owner, "Wedding", "EXPENSE", null);
        // The payees the rules name, as a check-in would meet them: uncategorised bank rows.
        spend(owner, everyday, "2026-08-19", "-18.50", "SQ *BLUE BOTTLE", null);
        spend(owner, everyday, "2026-08-20", "-56.00", "SHELL OIL 5746", null);
        spend(owner, everyday, "2026-06-14", "-640.00", "BLOOM AND VINE", null);
        // A payee rule exists only because a completed check-in wrote one (weekly-check-in.md,
        // decision 3). These are seeded as stored state, as CanvasWeek seeds the gym's charge
        // rule: not a figure, and every response below is still the application's own.
        rule("SQ *BLUE BOTTLE", seededCategoryId("Eating Out"));
        rule("SHELL OIL 5746", seededCategoryId("Fuel"));
        rule("BLOOM AND VINE", wedding);
        // The wedding is over: its rule now suggests nothing until the category is restored.
        ok(owner.post("/api/categories/" + wedding + "/archive", null), 200);
        name(stable);

        MobileRecorder record = phones.record();
        Caller you = phones.owner();
        Caller viewer = phones.viewer();

        record.in("categories");
        readCategories(record, you);
        // Names are unique per level, whatever the case.
        record.send(
                you, "POST", "/api/categories", "{\"name\":\"groceries\",\"kind\":\"EXPENSE\"}");

        record.in("categories-viewer");
        readCategories(record, viewer);
        record.send(viewer, "POST", "/api/categories", "{\"name\":\"Pets\",\"kind\":\"EXPENSE\"}");
        record.send(viewer, "DELETE", "/api/payee-rules/" + ruleOf("SQ *BLUE BOTTLE"), null);

        record.in("categories");
        ApiResponse pets =
                record.sendThen(
                        "category-added",
                        you,
                        "POST",
                        "/api/categories",
                        "{\"name\":\"Pets\",\"kind\":\"EXPENSE\"}");
        name(stable);
        record.in("category-added");
        readCategories(record, you);

        record.sendThen(
                "subcategory-added",
                you,
                "POST",
                "/api/categories",
                "{\"name\":\"Vet\",\"parentId\":\"%s\"}".formatted(pets.stringField("id")));
        name(stable);
        record.in("subcategory-added");
        readCategories(record, you);

        UUID eatingOut = seededCategoryId("Eating Out");
        record.sendThen(
                "category-edited",
                you,
                "PATCH",
                "/api/categories/" + eatingOut,
                "{\"version\":%d,\"name\":\"Dining\"}"
                        .formatted(version(owner, "/api/categories/" + eatingOut)));
        record.in("category-edited");
        readCategories(record, you);

        record.sendThen(
                "category-archived",
                you,
                "POST",
                "/api/categories/" + seededCategoryId("Shopping") + "/archive",
                null);
        record.in("category-archived");
        readCategories(record, you);

        record.sendThen(
                "category-unarchived",
                you,
                "POST",
                "/api/categories/" + wedding + "/unarchive",
                null);
        record.in("category-unarchived");
        readCategories(record, you);

        // Transport takes Fuel, Transit and Car with it, and the Shell rule on Fuel goes too.
        record.sendThen(
                "category-deleted",
                you,
                "DELETE",
                "/api/categories/" + seededCategoryId("Transport"),
                null);
        record.in("category-deleted");
        readCategories(record, you);

        record.sendThen(
                "rule-removed",
                you,
                "DELETE",
                "/api/payee-rules/" + ruleOf("SQ *BLUE BOTTLE"),
                null);
        record.in("rule-removed");
        readCategories(record, you);

        write(record, "categories", stable);
    }

    // ================================================================================ envelopes

    /**
     * August with an overspent envelope, an empty one, one with no category and an archived one;
     * July behind it and an empty September ahead; and every write: log, correct and remove an
     * entry, add, re-amount, archive, restore and delete an envelope.
     */
    @Test
    void the_committed_mobile_envelopes_fixtures_are_what_the_application_returns()
            throws IOException {
        Phones phones = seedPhones();
        ApiClient owner = phones.household().owner();
        Map<UUID, String> stable = new LinkedHashMap<>();

        UUID everyday = account(owner, "Everyday Checking", "4417", "CHECKING", "USD", "1641.75");
        UUID groceries = envelope(owner, "Groceries", "2026-08", "600.00", "Groceries");
        UUID eating = envelope(owner, "Eating Out", "2026-08", "150.00", "Eating Out");
        UUID fuel = envelope(owner, "Fuel", "2026-08", "120.00", "Fuel");
        UUID adhoc = envelope(owner, "Adhoc", "2026-08", "200.00", null);
        UUID holiday = envelope(owner, "Holiday", "2026-08", "300.00", null);
        UUID july = envelope(owner, "Groceries", "2026-07", "550.00", "Groceries");
        entry(owner, groceries, "86.40", "2026-08-03", "Greenfield Market", null);
        entry(owner, groceries, "142.10", "2026-08-12", "Greenfield Market", "big shop");
        // Attached, not typed: the figure is the transaction's, and the log says so.
        UUID grocer = spend(owner, everyday, "2026-08-15", "-54.20", "Corner Grocer", "Groceries");
        ok(
                owner.post(
                        "/api/envelopes/" + groceries + "/entries",
                        "{\"transactionId\":\"%s\"}".formatted(grocer)),
                201);
        entry(owner, eating, "41.20", "2026-08-09", "Corner Kitchen", null);
        entry(owner, eating, "120.00", "2026-08-14", "Harbour Grill", "anniversary");
        entry(owner, adhoc, "34.99", "2026-08-05", "Hardware Barn", null);
        entry(owner, holiday, "80.00", "2026-08-02", null, null);
        entry(owner, july, "498.30", "2026-07-28", "Greenfield Market", null);
        ok(owner.post("/api/envelopes/" + holiday + "/archive", null), 200);
        name(stable);

        MobileRecorder record = phones.record();
        Caller you = phones.owner();
        Caller viewer = phones.viewer();
        List<UUID> logs = List.of(groceries, eating, adhoc, holiday);
        String coffee = "{\"amount\":\"12.50\",\"date\":\"2026-08-20\"}";

        record.in("envelopes");
        readEnvelopes(record, you, logs);
        record.send(you, "GET", envelopes("2026-07"), null);
        record.send(you, "GET", entries(july), null);
        record.send(you, "GET", envelopes("2026-09"), null);

        record.in("envelopes-viewer");
        readEnvelopes(record, viewer, logs);
        record.send(viewer, "POST", "/api/envelopes/" + groceries + "/entries", coffee);

        record.in("envelopes");
        ApiResponse logged =
                record.sendThen(
                        "entry-logged",
                        you,
                        "POST",
                        "/api/envelopes/" + groceries + "/entries",
                        coffee);
        name(stable);
        String entry = "/api/envelopes/" + groceries + "/entries/" + logged.stringField("id");
        record.in("entry-logged");
        readEnvelopes(record, you, logs);

        record.sendThen(
                "entry-edited",
                you,
                "PATCH",
                entry,
                "{\"version\":%d,\"note\":\"coffee beans\"}"
                        .formatted(logged.numberField("version")));
        record.in("entry-edited");
        readEnvelopes(record, you, logs);

        record.sendThen("entry-deleted", you, "DELETE", entry, null);
        record.in("entry-deleted");
        readEnvelopes(record, you, logs);

        record.sendThen(
                "envelope-added",
                you,
                "POST",
                "/api/envelopes",
                "{\"name\":\"Coffee\",\"period\":\"2026-08\",\"amount\":\"40.00\"}");
        name(stable);
        record.in("envelope-added");
        readEnvelopes(record, you, logs);

        record.sendThen(
                "envelope-edited",
                you,
                "PATCH",
                "/api/envelopes/" + groceries,
                "{\"version\":%d,\"amount\":\"650.00\"}"
                        .formatted(version(owner, "/api/envelopes/" + groceries)));
        record.in("envelope-edited");
        readEnvelopes(record, you, logs);

        record.sendThen(
                "envelope-archived", you, "POST", "/api/envelopes/" + fuel + "/archive", null);
        record.in("envelope-archived");
        readEnvelopes(record, you, logs);

        record.sendThen(
                "envelope-unarchived",
                you,
                "POST",
                "/api/envelopes/" + holiday + "/unarchive",
                null);
        record.in("envelope-unarchived");
        readEnvelopes(record, you, logs);

        record.sendThen("envelope-deleted", you, "DELETE", "/api/envelopes/" + adhoc, null);
        record.in("envelope-deleted");
        readEnvelopes(record, you, logs.subList(0, 2));
        record.send(you, "GET", entries(holiday), null);

        write(record, "envelopes", stable);
    }

    // ================================================================================= helpers

    /**
     * A USD household, phones for the owner and the viewer, and the recorder they speak through.
     */
    private record Phones(
            Household household, MobileRecorder record, Caller owner, Caller viewer) {}

    /**
     * {@code DemoFixtureIT.seedDemoHousehold}'s household (USD, Ada, Grace and Alan), with a phone
     * token each for the owner and the viewer, issued off the record and named in the file.
     */
    private Phones seedPhones() {
        ok(
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
        Household household =
                new Household(
                        owner,
                        signIn(MEMBER_EMAIL, MEMBER_SECRET),
                        signIn(VIEWER_EMAIL, VIEWER_SECRET));

        MobileRecorder record = new MobileRecorder();
        String ownerToken = issueToken(OWNER_EMAIL, OWNER_SECRET, DemoFixtureIT.PHONE_LABEL);
        String viewerToken = issueToken(VIEWER_EMAIL, VIEWER_SECRET, DemoFixtureIT.PHONE_LABEL);
        record.secret(ownerToken, "fixture-token-owner");
        record.secret(viewerToken, "fixture-token-viewer");

        // Any state: a phone asks who the server is whenever it starts.
        record.in("*");
        record.send(Caller.stranger(anonymous()), "GET", "/api/meta", null);
        return new Phones(
                household,
                record,
                new Caller("owner", anonymous().withBearerToken(ownerToken), Map.of()),
                new Caller("viewer", anonymous().withBearerToken(viewerToken), Map.of()));
    }

    /**
     * What a signed-in phone reads on arrival — the transactions tab is the first screen and stays
     * alive under the others, so every state needs it — and what a write's refresh asks again.
     */
    private static void readAll(MobileRecorder record, Caller who) {
        record.send(who, "GET", "/api/auth/me", null);
        record.send(who, "GET", DemoFixtureIT.ACCOUNTS, null);
        record.send(who, "GET", DemoFixtureIT.CATEGORIES, null);
        record.send(who, "GET", DemoFixtureIT.LEDGER, null);
    }

    private static void readCategories(MobileRecorder record, Caller who) {
        readAll(record, who);
        record.send(who, "GET", PAYEE_RULES, null);
    }

    private static void readEnvelopes(MobileRecorder record, Caller who, List<UUID> logs) {
        readAll(record, who);
        record.send(who, "GET", PERIODS, null);
        record.send(who, "GET", envelopes("2026-08"), null);
        for (UUID envelope : logs) {
            record.send(who, "GET", entries(envelope), null);
        }
    }

    private void write(MobileRecorder record, String area, Map<UUID, String> stable)
            throws IOException {
        name(stable);
        // The recorder's header names DemoFixtureIT; this file is regenerated by this class.
        String rendered =
                record.render(area)
                        .replace("backend DemoFixtureIT", "backend MobileAccountsFixtureIT")
                        .replace("-Dtest=DemoFixtureIT", "-Dit.test=MobileAccountsFixtureIT");
        DemoFixtureIT.compareOrWriteMobile(
                DemoFixtureIT.canonicaliseMobile(rendered, stable),
                DemoFixtureIT.MOBILE_FIXTURES.resolve(area + ".generated.json"),
                Path.of("target", "mobile-" + area + ".generated.json"));
    }

    private static UUID account(
            ApiClient owner,
            String name,
            String lastFour,
            String type,
            String currency,
            String openingBalance) {
        return id(
                ok(
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
                                                openingBalance)),
                        201));
    }

    /** A transaction with a payee, filed under a category by name, or under none. */
    private UUID spend(
            ApiClient owner,
            UUID account,
            String date,
            String amount,
            String payee,
            String categoryName) {
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
                                                        : "\""
                                                                + seededCategoryId(categoryName)
                                                                + "\"")),
                        201));
    }

    private UUID envelope(
            ApiClient owner, String name, String period, String amount, String categoryName) {
        return id(
                ok(
                        owner.post(
                                "/api/envelopes",
                                """
                                {"name":"%s","period":"%s","amount":"%s"%s}
                                """
                                        .formatted(
                                                name,
                                                period,
                                                amount,
                                                categoryName == null
                                                        ? ""
                                                        : ",\"categoryId\":\""
                                                                + seededCategoryId(categoryName)
                                                                + "\"")),
                        201));
    }

    private static void entry(
            ApiClient owner, UUID envelope, String amount, String date, String payee, String note) {
        StringBuilder body =
                new StringBuilder("{\"amount\":\"%s\",\"date\":\"%s\"".formatted(amount, date));
        if (payee != null) {
            body.append(",\"payeeName\":\"").append(payee).append('"');
        }
        if (note != null) {
            body.append(",\"note\":\"").append(note).append('"');
        }
        ok(owner.post("/api/envelopes/" + envelope + "/entries", body.append('}').toString()), 201);
    }

    /** A payee rule as a completed check-in leaves one: this payee, filed under that category. */
    private void rule(String payee, UUID category) {
        int inserted =
                jdbc.update(
                        """
                        INSERT INTO payee_rules (id, household_id, payee_id, category_id, created_by)
                        SELECT gen_random_uuid(), p.household_id, p.id, ?, ?
                          FROM payees p WHERE p.name = ?
                        """,
                        category,
                        userIdOf(OWNER_EMAIL),
                        payee);
        if (inserted != 1) {
            throw new AssertionError("could not seed the payee rule for " + payee);
        }
    }

    private UUID ruleOf(String payee) {
        return jdbc.queryForObject(
                "SELECT r.id FROM payee_rules r JOIN payees p ON p.id = r.payee_id WHERE p.name = ?",
                UUID.class,
                payee);
    }

    /** The version a row has now, read the way the phone reads it. */
    private static long version(ApiClient client, String path) {
        return ok(client.get(path), 200).numberField("version");
    }

    /**
     * Names every row these screens can show by what it is, so a regeneration that meets the same
     * rows in another order — same-date ledger rows and log entries sort by random id — writes the
     * same names. Called after every write that makes rows, while they exist.
     */
    private void name(Map<UUID, String> stable) {
        Set<String> used = new HashSet<>(stable.values());
        nameAll(
                stable,
                used,
                "SELECT id, 'acc-' || name AS label FROM accounts ORDER BY name, created_at");
        nameAll(
                stable,
                used,
                """
                SELECT c.id, 'cat-' || coalesce(p.name || '-', '') || c.name AS label
                  FROM categories c LEFT JOIN categories p ON p.id = c.parent_id
                 ORDER BY label
                """);
        nameAll(
                stable,
                used,
                """
                SELECT t.id, 'txn-' || t.date || '-' || a.name || '-' || t.amount AS label
                  FROM transactions t JOIN accounts a ON a.id = t.account_id
                 ORDER BY t.date, a.name, t.amount
                """);
        nameAll(stable, used, "SELECT id, 'payee-' || name AS label FROM payees ORDER BY name");
        nameAll(
                stable,
                used,
                "SELECT id, 'env-' || period || '-' || name AS label FROM envelopes ORDER BY label");
        nameAll(
                stable,
                used,
                """
                SELECT n.id, 'ent-' || n.date || '-' || n.amount AS label
                  FROM envelope_entries n ORDER BY n.date, n.amount, n.created_at
                """);
        nameAll(
                stable,
                used,
                """
                SELECT r.id, 'rule-' || p.name AS label
                  FROM payee_rules r JOIN payees p ON p.id = r.payee_id ORDER BY p.name
                """);
    }

    private void nameAll(Map<UUID, String> stable, Set<String> used, String sql) {
        for (Map<String, Object> row : jdbc.queryForList(sql)) {
            UUID id = (UUID) row.get("id");
            if (stable.containsKey(id)) {
                continue;
            }
            String base =
                    ((String) row.get("label"))
                            .toLowerCase(Locale.ROOT)
                            .replaceAll("[^a-z0-9]+", "-")
                            .replaceAll("-+$", "");
            String name = base;
            for (int n = 2; used.contains(name); n++) {
                name = base + "-" + n;
            }
            used.add(name);
            stable.put(id, name);
        }
    }

    private static ApiResponse ok(ApiResponse response, int status) {
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
