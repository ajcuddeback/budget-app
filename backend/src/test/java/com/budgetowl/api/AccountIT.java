package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.account.domain.AccountType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The accounts API, over real HTTP against a real PostgreSQL.
 *
 * <p>Covers the mandatory list in docs/guides/testing-style.md for every one of the seven routes —
 * {@code 401}, {@code 404} for an id that is not the caller's, {@code 400} naming the field, the
 * happy path, a {@code VIEWER} refused every write, and identical behaviour on the session and
 * bearer transports. The cross-household case has an integration test of its own ({@code
 * AccountCrossHouseholdIT}), because seeding a second household means dropping an index; the
 * balance arithmetic and the two transaction-dependent {@code 409}s have theirs ({@code
 * AccountBalanceIT}); and {@code AccountDisclosureIT} is the one that proves {@code lastFour} never
 * reaches a log.
 */
class AccountIT extends ApiTestBase {

    private static final String CHECKING =
            """
            {"name":"Current","lastFour":"4412","type":"CHECKING","currency":"GBP",
             "openingBalance":"1234.5600"}
            """;

    // ---------------------------------------------------------------------------- happy paths

    @Test
    void createsAnAccountAndSaysWhereItLives() {
        Household household = seedHousehold();

        ApiResponse response = household.owner().post("/api/accounts", CHECKING);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.header("Location"))
                .contains("/api/accounts/" + response.stringField("id"));
        assertThat(response.stringField("name")).isEqualTo("Current");
        assertThat(response.stringField("lastFour")).isEqualTo("4412");
        assertThat(response.stringField("type")).isEqualTo("CHECKING");
        assertThat(response.stringField("currency")).isEqualTo("GBP");
        assertThat(response.body()).contains("\"archived\":false");
        assertThat(response.numberField("version")).isZero();
    }

    @Test
    void doesNotPutTheDigitsInTheLocationHeader() {
        // A Location carrying a partial financial identifier would land in every access log
        // between here and the client (ADR-0028).
        Household household = seedHousehold();

        ApiResponse response = household.owner().post("/api/accounts", CHECKING);

        assertThat(response.header("Location")).get().asString().doesNotContain("4412");
    }

    @Test
    void returnsTheNameAndTheDigitsSeparatelyAndNeverJoined() {
        // The client composes `name •1234`; a pre-joined label cannot be re-ordered for a
        // right-to-left locale (ADR-0023, ADR-0028).
        Household household = seedHousehold();

        ApiResponse response = household.owner().post("/api/accounts", CHECKING);

        assertThat(response.body()).doesNotContain("Current •4412").doesNotContain("•");
    }

    @ParameterizedTest
    @EnumSource(AccountType.class)
    void acceptsEveryTypeTheDomainKnows(AccountType type) {
        // Enumerated from the enum itself, so adding a sixth type cannot be forgotten here.
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .owner()
                        .post(
                                "/api/accounts",
                                """
                                {"name":"Somewhere","type":"%s","currency":"GBP",
                                 "openingBalance":"0"}
                                """
                                        .formatted(type));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.stringField("type")).isEqualTo(type.name());
    }

    @Test
    void readsOneAccountBack() {
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Savings", "9001");

        ApiResponse response = household.owner().get("/api/accounts/" + id);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.stringField("id")).isEqualTo(id.toString());
        assertThat(response.stringField("lastFour")).isEqualTo("9001");
    }

    @Test
    void listsAccountsByNameCaseInsensitivelyWithAStableTiebreak() {
        Household household = seedHousehold();
        createAccount(household.owner(), "zebra", null);
        createAccount(household.owner(), "Apple", null);
        createAccount(household.owner(), "banana", null);

        ApiResponse response = household.owner().get("/api/accounts");

        assertThat(response.status()).isEqualTo(200);
        assertThat(namesIn(response)).containsExactly("Apple", "banana", "zebra");
        assertThat(response.numberField("totalElements")).isEqualTo(3);
    }

    @Test
    void allowsTwoAccountsWithTheSameName() {
        // Real households have two pots called "Savings", and nothing may assume otherwise —
        // not a lookup, not a fixture, not an import (ADR-0028 is why lastFour exists).
        Household household = seedHousehold();
        UUID first = createAccount(household.owner(), "Savings", "1111");
        UUID second = createAccount(household.owner(), "Savings", "2222");

        ApiResponse response = household.owner().get("/api/accounts");

        assertThat(first).isNotEqualTo(second);
        assertThat(namesIn(response)).containsExactly("Savings", "Savings");
    }

    @Test
    void answersTheEmptyStateRatherThanAnErrorForAHouseholdWithNoAccounts() {
        Household household = seedHousehold();

        ApiResponse response = household.owner().get("/api/accounts");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("\"content\":[]");
        assertThat(response.numberField("totalElements")).isZero();
    }

    @Test
    void hidesArchivedAccountsUntilTheyAreAskedFor() {
        Household household = seedHousehold();
        UUID retired = createAccount(household.owner(), "Old card", "3333");
        createAccount(household.owner(), "Current", "4412");

        assertThat(household.owner().post("/api/accounts/" + retired + "/archive", null).status())
                .isEqualTo(200);

        assertThat(namesIn(household.owner().get("/api/accounts"))).containsExactly("Current");
        assertThat(namesIn(household.owner().get("/api/accounts?includeArchived=true")))
                .containsExactly("Current", "Old card");
    }

    @Test
    void archivingIsIdempotentAndUnarchivingBringsItBack() {
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Old card", null);

        household.owner().post("/api/accounts/" + id + "/archive", null);
        ApiResponse again = household.owner().post("/api/accounts/" + id + "/archive", null);
        assertThat(again.status())
                .as("the state the caller asked for, not an error")
                .isEqualTo(200);
        assertThat(again.body()).contains("\"archived\":true");

        ApiResponse back = household.owner().post("/api/accounts/" + id + "/unarchive", null);
        assertThat(back.body()).contains("\"archived\":false");
    }

    @Test
    void keepsAnArchivedAccountsBalanceCorrect() {
        // Archiving hides; it does not zero.
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Old card", null);

        household.owner().post("/api/accounts/" + id + "/archive", null);
        ApiResponse response = household.owner().get("/api/accounts/" + id);

        assertThat(response.stringField("balance")).isEqualTo("100.0000");
    }

    @Test
    void deletesAnEmptyAccountOutright() {
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Mistake", null);

        assertThat(household.owner().delete("/api/accounts/" + id).status()).isEqualTo(204);

        assertThat(household.owner().get("/api/accounts/" + id).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts", Long.class)).isZero();
    }

    // --------------------------------------------------------------------------------- updates

    @Test
    void changesOnlyTheFieldsTheUpdateMentions() {
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Currant", "4412");

        ApiResponse response =
                household
                        .owner()
                        .patch("/api/accounts/" + id, "{\"version\":0,\"name\":\"Current\"}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.stringField("name")).isEqualTo("Current");
        assertThat(response.stringField("lastFour"))
                .as("an absent field is unchanged, not cleared")
                .isEqualTo("4412");
        assertThat(response.stringField("type")).isEqualTo("CHECKING");
        assertThat(response.stringField("openingBalance")).isEqualTo("100.0000");
        assertThat(response.numberField("version")).isEqualTo(1);
    }

    @Test
    void clearsTheDigitsOnlyWhenAskedExplicitly() {
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", "4412");

        ApiResponse response =
                household.owner().patch("/api/accounts/" + id, "{\"version\":0,\"lastFour\":null}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("\"lastFour\":null");
    }

    @Test
    void storesTheTrimmedName() {
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", null);

        ApiResponse response =
                household
                        .owner()
                        .patch("/api/accounts/" + id, "{\"version\":0,\"name\":\"  Renamed  \"}");

        assertThat(response.stringField("name")).isEqualTo("Renamed");
        assertThat(jdbc.queryForObject("SELECT name FROM accounts WHERE id = ?", String.class, id))
                .isEqualTo("Renamed");
    }

    @Test
    void changesTheTypeFreely() {
        // Nothing about stored money depends on the type.
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", null);

        ApiResponse response =
                household
                        .owner()
                        .patch("/api/accounts/" + id, "{\"version\":0,\"type\":\"CREDIT_CARD\"}");

        assertThat(response.stringField("type")).isEqualTo("CREDIT_CARD");
    }

    @Test
    void changesTheCurrencyWhileTheAccountHasNoTransactions() {
        // Before the first transaction there is one amount to reinterpret and the user is the
        // person who typed it. After, it is a 409 — see AccountBalanceIT.
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", null);

        ApiResponse response =
                household
                        .owner()
                        .patch("/api/accounts/" + id, "{\"version\":0,\"currency\":\"EUR\"}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.stringField("currency")).isEqualTo("EUR");
        assertThat(response.stringField("openingBalance"))
                .as("the figures do not move; only what they are denominated in does")
                .isEqualTo("100.0000");
    }

    @Test
    void acceptsTheCurrencyItJustReturnedWithoutCallingItAChange() {
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", null);

        ApiResponse response =
                household
                        .owner()
                        .patch("/api/accounts/" + id, "{\"version\":0,\"currency\":\"GBP\"}");

        assertThat(response.status())
                .as("a client that patches the whole form back must not be refused")
                .isEqualTo(200);
    }

    @Test
    void acceptsANewCurrencyAndANewAmountInOneRequest() {
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", null);

        ApiResponse response =
                household
                        .owner()
                        .patch(
                                "/api/accounts/" + id,
                                """
                                {"version":0,"currency":"EUR","openingBalance":"-400.0000"}
                                """);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.stringField("currency")).isEqualTo("EUR");
        assertThat(response.stringField("openingBalance")).isEqualTo("-400.0000");
    }

    @Test
    void correctsAMistypedOpeningBalanceAndTheBalanceFollows() {
        // Routine bookkeeping, not a rewrite of history.
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Card", null);

        ApiResponse response =
                household
                        .owner()
                        .patch(
                                "/api/accounts/" + id,
                                "{\"version\":0,\"openingBalance\":\"-400.0000\"}");

        assertThat(response.stringField("openingBalance")).isEqualTo("-400.0000");
        assertThat(response.stringField("balance")).isEqualTo("-400.0000");
    }

    @Test
    void answers409WhenSomebodyElseSavedFirst() {
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", null);
        household.owner().patch("/api/accounts/" + id, "{\"version\":0,\"name\":\"Theirs\"}");

        ApiResponse stale =
                household
                        .member()
                        .patch("/api/accounts/" + id, "{\"version\":0,\"name\":\"Mine\"}");

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.stringField("code")).isEqualTo("stale-version");
        assertThat(household.owner().get("/api/accounts/" + id).stringField("name"))
                .as("the loser is told rather than the winner being overwritten")
                .isEqualTo("Theirs");
    }

    // ------------------------------------------------------------------------- exact amounts

    @Test
    void roundTripsAnOpeningBalanceExactly() {
        Household household = seedHousehold();

        ApiResponse created =
                household
                        .owner()
                        .post(
                                "/api/accounts",
                                """
                                {"name":"Odd","type":"SAVINGS","currency":"GBP",
                                 "openingBalance":"-1234.5678"}
                                """);

        assertThat(created.stringField("openingBalance")).isEqualTo("-1234.5678");
        assertThat(created.stringField("balance")).isEqualTo("-1234.5678");
        assertThat(
                        household
                                .owner()
                                .get("/api/accounts/" + created.stringField("id"))
                                .stringField("openingBalance"))
                .isEqualTo("-1234.5678");
        assertThat(jdbc.queryForObject("SELECT opening_balance FROM accounts", BigDecimal.class))
                .isEqualByComparingTo("-1234.5678");
    }

    @Test
    void putsEveryAmountOnTheWireAsAStringAndNeverAsAJsonNumber() {
        // A double cannot hold every NUMERIC(19,4), and the one that gets rounded will be
        // somebody's money (ADR-0006, docs/memory/gotchas.md).
        Household household = seedHousehold();
        createAccount(household.owner(), "Current", null);

        String listed = household.owner().get("/api/accounts").body();

        assertThat(listed).contains("\"openingBalance\":\"100.0000\"");
        assertThat(listed).contains("\"balance\":\"100.0000\"");
        assertThat(listed).doesNotContain("\"openingBalance\":100");
        assertThat(listed).doesNotContain("\"balance\":100");
    }

    @Test
    void refusesAJsonNumberWhereAnAmountIsExpected() {
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .owner()
                        .post(
                                "/api/accounts",
                                """
                                {"name":"Current","type":"CHECKING","currency":"GBP",
                                 "openingBalance":1234.56}
                                """);

        assertThat(response.status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts", Long.class)).isZero();
    }

    @Test
    void treatsAnOpeningBalanceOfZeroAsAValueRatherThanAsUnset() {
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .owner()
                        .post(
                                "/api/accounts",
                                """
                                {"name":"New pot","type":"SAVINGS","currency":"GBP",
                                 "openingBalance":"0"}
                                """);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.stringField("openingBalance")).isEqualTo("0.0000");
    }

    // ---------------------------------------------------------------------------- pagination

    @Test
    void clampsAPageSizeOverTheMaximumRatherThanRejectingIt() {
        Household household = seedHousehold();
        createAccount(household.owner(), "Current", null);

        ApiResponse response = household.owner().get("/api/accounts?size=5000");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.numberField("size")).isEqualTo(200);
    }

    @Test
    void pagesInTheQueryWithAStableOrderAcrossPages() {
        Household household = seedHousehold();
        createAccount(household.owner(), "Savings", "1111");
        createAccount(household.owner(), "Savings", "2222");
        createAccount(household.owner(), "Savings", "3333");

        List<String> first = idsIn(household.owner().get("/api/accounts?page=0&size=2"));
        List<String> second = idsIn(household.owner().get("/api/accounts?page=1&size=2"));

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(1);
        assertThat(first).doesNotContainAnyElementsOf(second);
    }

    // -------------------------------------------------------------------------------- errors

    /** Method, path and body for each of the seven routes, with {@code {id}} to be substituted. */
    static List<Endpoint> everyRoute() {
        return List.of(
                new Endpoint("GET", "/api/accounts", null),
                new Endpoint("POST", "/api/accounts", CHECKING),
                new Endpoint("GET", "/api/accounts/{id}", null),
                new Endpoint("PATCH", "/api/accounts/{id}", "{\"version\":0,\"name\":\"Renamed\"}"),
                new Endpoint("POST", "/api/accounts/{id}/archive", null),
                new Endpoint("POST", "/api/accounts/{id}/unarchive", null),
                new Endpoint("DELETE", "/api/accounts/{id}", null));
    }

    /** The six that change something. A {@code VIEWER} gets {@code 403} from every one. */
    static List<Endpoint> everyWrite() {
        return everyRoute().stream().filter(endpoint -> !"GET".equals(endpoint.method())).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRoute")
    void answers401ToAnUnauthenticatedCaller(Endpoint endpoint) {
        seedHousehold();

        ApiResponse response = endpoint.callWith(browser(), UUID.randomUUID());

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.stringField("code")).isEqualTo("not-authenticated");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRoute")
    void answers404ForAnIdThatBelongsToNothing(Endpoint endpoint) {
        Household household = seedHousehold();
        createAccount(household.owner(), "Current", "4412");

        ApiResponse response = endpoint.callWith(household.owner(), UUID.randomUUID());

        assertThat(response.status()).isIn(200, 201, 404);
        if (endpoint.path().contains("{id}")) {
            assertThat(response.status()).isEqualTo(404);
            assertThat(response.stringField("code")).isEqualTo("not-found");
        }
    }

    @Test
    void answers404ForAnIdFromAnotherTablesRows() {
        // A membership id is a perfectly well-formed UUID that is not an account.
        Household household = seedHousehold();

        ApiResponse response =
                household.owner().get("/api/accounts/" + membershipIdOf(OWNER_EMAIL));

        assertThat(response.status()).isEqualTo(404);
    }

    static List<String> invalidBodies() {
        return List.of(
                """
                {"name":"","type":"CHECKING","currency":"GBP","openingBalance":"0"}|name""",
                """
                {"name":"Current","type":"CHECKING","currency":"GBP","openingBalance":"0",
                 "lastFour":"12345"}|lastFour""",
                """
                {"name":"Current","type":"CHECKING","currency":"GBP",
                 "openingBalance":"1.23456"}|openingBalance""",
                """
                {"name":"Current","type":"CHECKING","currency":"GB","openingBalance":"0"}|currency""",
                """
                {"name":"Current","type":"","currency":"GBP","openingBalance":"0"}|type""");
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void answers400NamingTheFieldThatIsWrong(String bodyAndField) {
        Household household = seedHousehold();
        String[] parts = bodyAndField.split("\\|");

        ApiResponse response = household.owner().post("/api/accounts", parts[0]);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.stringField("code")).isEqualTo("validation-failed");
        assertThat(response.body()).contains(parts[1].strip());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts", Long.class)).isZero();
    }

    @Test
    void answers400NamingTheFieldForAnUnknownCurrency() {
        // Three uppercase letters and not a currency: the shape passes the edge and the ISO-4217
        // table refuses it in the service.
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .owner()
                        .post(
                                "/api/accounts",
                                """
                                {"name":"Current","type":"CHECKING","currency":"ZZZ",
                                 "openingBalance":"0"}
                                """);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.stringField("code")).isEqualTo("validation-failed");
        assertThat(response.body()).contains("currency");
    }

    @Test
    void answers400NamingTheFieldForATypeThatIsNotInTheClosedSet() {
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .owner()
                        .post(
                                "/api/accounts",
                                """
                                {"name":"Current","type":"SPACESHIP","currency":"GBP",
                                 "openingBalance":"0"}
                                """);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.stringField("code")).isEqualTo("validation-failed");
        assertThat(response.body()).contains("type");
    }

    @Test
    void refusesAnUnknownPropertyRatherThanIgnoringIt() {
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .owner()
                        .post(
                                "/api/accounts",
                                """
                                {"name":"Current","type":"CHECKING","currency":"GBP",
                                 "openingBalance":"0","householdId":"%s","archived":true}
                                """
                                        .formatted(UUID.randomUUID()));

        assertThat(response.status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts", Long.class)).isZero();
    }

    @Test
    void refusesAnUpdateWithNoVersion() {
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", null);

        ApiResponse response = household.owner().patch("/api/accounts/" + id, "{\"name\":\"X\"}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("version");
    }

    // --------------------------------------------------------------------------------- roles

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void answers403ToAViewerOnEveryWrite(Endpoint endpoint) {
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", "4412");

        ApiResponse response = endpoint.callWith(household.viewer(), id);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.stringField("code")).isEqualTo("read-only-role");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void refusesAViewerBeforeLookingTheAccountUp(Endpoint endpoint) {
        // The role is checked first on purpose: a 404 for an unknown id and a 403 for a refused
        // role must not combine into an oracle for which ids exist.
        Household household = seedHousehold();

        ApiResponse response = endpoint.callWith(household.viewer(), UUID.randomUUID());

        assertThat(response.status()).isEqualTo(403);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void letsAMemberWriteEverythingAnOwnerCan(Endpoint endpoint) {
        // Accounts are not administrative: OWNER and MEMBER both write (docs/features/accounts.md).
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", "4412");

        ApiResponse response = endpoint.callWith(household.member(), id);

        assertThat(response.status()).isIn(200, 201, 204);
    }

    @Test
    void letsAViewerReadTheAccountsAndTheirBalances() {
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", "4412");

        assertThat(household.viewer().get("/api/accounts").status()).isEqualTo(200);
        assertThat(household.viewer().get("/api/accounts/" + id).stringField("balance"))
                .isEqualTo("100.0000");
    }

    @Test
    void answers403NotACrashForASignedInUserWithNoMembership() {
        Household household = seedHousehold();
        acceptInvitation(
                invite(household.owner(), OUTSIDER_EMAIL, "MEMBER"),
                "Edsger",
                "edsger-example-passphrase");
        household
                .owner()
                .delete("/api/households/current/members/" + membershipIdOf(OUTSIDER_EMAIL));
        ApiClient outsider = signIn(OUTSIDER_EMAIL, "edsger-example-passphrase");

        ApiResponse response = outsider.get("/api/accounts");

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.stringField("code")).isEqualTo("not-a-member");
    }

    // ----------------------------------------------------------------------------- transports

    @ParameterizedTest
    @ValueSource(strings = {"/api/accounts", "/api/accounts?includeArchived=true"})
    void answersIdenticallyOnBothTransportsForAList(String path) {
        Household household = seedHousehold();
        createAccount(household.owner(), "Current", "4412");
        ApiClient phone = mobile(OWNER_EMAIL, OWNER_SECRET, "Ada's phone");

        ApiResponse overSession = household.owner().get(path);
        ApiResponse overBearer = phone.get(path);

        assertThat(overBearer.status()).isEqualTo(overSession.status());
        assertThat(overBearer.body()).isEqualTo(overSession.body());
    }

    @Test
    void answersIdenticallyOnBothTransportsForOneAccount() {
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", "4412");
        ApiClient phone = mobile(OWNER_EMAIL, OWNER_SECRET, "Ada's phone");

        ApiResponse overSession = household.owner().get("/api/accounts/" + id);
        ApiResponse overBearer = phone.get("/api/accounts/" + id);

        assertThat(overBearer.body()).isEqualTo(overSession.body());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void letsTheMobileTransportDoEveryWriteTheBrowserCan(Endpoint endpoint) {
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", "4412");
        ApiClient phone = mobile(OWNER_EMAIL, OWNER_SECRET, "Ada's phone");

        ApiResponse response = endpoint.callWith(phone, id);

        assertThat(response.status()).isIn(200, 201, 204);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void refusesAViewersWriteTheSameWayOnBothTransports(Endpoint endpoint) {
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", "4412");
        ApiClient phone = mobile(VIEWER_EMAIL, VIEWER_SECRET, "Alan's phone");

        ApiResponse overSession = endpoint.callWith(household.viewer(), id);
        ApiResponse overBearer = endpoint.callWith(phone, id);

        assertThat(overSession.status()).isEqualTo(403);
        assertThat(overBearer.bodyWithoutCorrelationId())
                .isEqualTo(overSession.bodyWithoutCorrelationId());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void refusesAStateChangingSessionRequestWithNoCsrfToken(Endpoint endpoint) {
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", "4412");

        ApiResponse response = endpoint.callWith(household.owner().withoutCsrfToken(), id);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.stringField("code")).isEqualTo("csrf-token-required");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void asksNoCsrfTokenOfTheBearerTransport(Endpoint endpoint) {
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", "4412");
        ApiClient phone = mobile(OWNER_EMAIL, OWNER_SECRET, "Ada's phone").withoutCsrfToken();

        ApiResponse response = endpoint.callWith(phone, id);

        assertThat(response.status()).isIn(200, 201, 204);
    }

    // --------------------------------------------------------------------------------- helpers

    private static List<String> namesIn(ApiResponse response) {
        return valuesOf("name", response.body());
    }

    private static List<String> idsIn(ApiResponse response) {
        return valuesOf("id", response.body());
    }

    private static List<String> valuesOf(String field, String body) {
        return java.util.regex.Pattern.compile("\"" + field + "\":\"([^\"]*)\"")
                .matcher(body)
                .results()
                .map(match -> match.group(1))
                .toList();
    }

    record Endpoint(String method, String path, String body) {

        ApiResponse callWith(ApiClient client, UUID accountId) {
            return client.send(method, path.replace("{id}", accountId.toString()), body);
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }
}
