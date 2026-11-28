package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Who may read a report, over which transport, and that nothing may write one
 * (docs/features/reporting.md, Testing notes: {@code ReportAccessIT}).
 *
 * <p>There is no write route, so the mandatory {@code VIEWER}-gets-{@code 403} and CSRF tests do
 * not apply. In their place: a {@code VIEWER} gets {@code 200} with figures <b>identical</b> to an
 * {@code OWNER}'s, a user with no membership gets {@code 403 not-a-member}, and every other method
 * is a {@code 405} — so a write cannot appear under {@code /api/reports} without this failing.
 */
class ReportAccessIT extends ReportTestBase {

    private static final String QUERY = "?from=2026-07&to=2026-08";

    private Household household;

    static List<String> everyReport() {
        return List.of(
                "/api/reports/income-and-spending" + QUERY,
                "/api/reports/spending-by-category" + QUERY);
    }

    /** Both reports, in both representations. */
    static Stream<Arguments> everyRepresentation() {
        return everyReport().stream()
                .flatMap(
                        path ->
                                Stream.of(
                                        Arguments.of(path, "application/json"),
                                        Arguments.of(path, "text/csv")));
    }

    /** Every method that is not GET, on both report paths. */
    static Stream<Arguments> everyOtherMethod() {
        return everyReport().stream()
                .flatMap(
                        path ->
                                Stream.of("POST", "PUT", "PATCH", "DELETE")
                                        .map(method -> Arguments.of(method, path)));
    }

    @BeforeEach
    void seedAHouseholdWithAMonthOfHistory() {
        household = seedHousehold();
        UUID current = account(household.owner(), "Current", "GBP");
        createTransaction(
                household.owner(), current, "2026-07-01", "2400.00", seededCategoryId("Salary"));
        createTransaction(
                household.owner(), current, "2026-07-03", "-61.20", seededCategoryId("Groceries"));
        createTransaction(household.owner(), current, "2026-08-04", "-9.99", null);
    }

    // --------------------------------------------------------------------- authentication

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("everyRepresentation")
    void answers401ToAnUnauthenticatedBrowser(String path, String accept) {
        ApiResponse response = browser().getAccepting(path, accept);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.header("Content-Type")).get().asString().contains("problem+json");
        assertThat(response.body()).doesNotContain("2400").doesNotContain("Groceries");
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("everyRepresentation")
    void answers401ToAnUnknownBearerToken(String path, String accept) {
        ApiResponse response =
                anonymous()
                        .withBearerToken("not-a-token-anybody-issued")
                        .getAccepting(path, accept);

        assertThat(response.status()).isEqualTo(401);
    }

    // ------------------------------------------------------------------------------ roles

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("everyRepresentation")
    void aViewerGetsFiguresIdenticalToAnOwners(String path, String accept) {
        ApiResponse owner = household.owner().getAccepting(path, accept);
        ApiResponse viewer = household.viewer().getAccepting(path, accept);
        ApiResponse member = household.member().getAccepting(path, accept);

        assertThat(owner.status()).isEqualTo(200);
        assertThat(viewer.status()).isEqualTo(200);
        assertThat(viewer.body()).isEqualTo(owner.body());
        assertThat(member.body()).isEqualTo(owner.body());
        assertThat(owner.body()).as("the figures are really there").contains("61.2000");
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("everyRepresentation")
    void answers403NotAMemberToASignedInUserWithNoHousehold(String path, String accept) {
        acceptInvitation(
                invite(household.owner(), OUTSIDER_EMAIL, "MEMBER"),
                "Edsger",
                "edsger-example-passphrase");
        household
                .owner()
                .delete("/api/households/current/members/" + membershipIdOf(OUTSIDER_EMAIL));
        ApiClient removed = signIn(OUTSIDER_EMAIL, "edsger-example-passphrase");

        ApiResponse response = removed.getAccepting(path, accept);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.stringField("code")).isEqualTo("not-a-member");
        assertThat(response.body()).doesNotContain("2400").doesNotContain("Groceries");
    }

    // ------------------------------------------------------------------------- transports

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("everyRepresentation")
    void answersIdenticallyOverASessionAndABearerToken(String path, String accept) {
        ApiClient phone = mobile(OWNER_EMAIL, OWNER_SECRET, "Ada's phone");

        ApiResponse overSession = household.owner().getAccepting(path, accept);
        ApiResponse overBearer = phone.getAccepting(path, accept);

        assertThat(overBearer.status()).isEqualTo(200);
        assertThat(overBearer.body()).isEqualTo(overSession.body());
        assertThat(overBearer.headerValues("set-cookie"))
                .as("a bearer read is not issued a session it never asked for")
                .noneMatch(cookie -> cookie.startsWith("SESSION="));
    }

    // ------------------------------------------------------------------------- no writes

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyOtherMethod")
    void refusesEveryMethodButGetOverASession(String method, String path) {
        // A browser with its CSRF token, so the refusal is the route's and not the CSRF filter's.
        ApiResponse response = household.owner().send(method, path, "{}");

        assertThat(response.status()).isEqualTo(405);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyOtherMethod")
    void refusesEveryMethodButGetOverABearerToken(String method, String path) {
        ApiResponse response =
                mobile(OWNER_EMAIL, OWNER_SECRET, "Ada's phone").send(method, path, "{}");

        assertThat(response.status()).isEqualTo(405);
    }

    // --------------------------------------------------------------------- representations

    @Test
    void answersJsonWhenTheCallerStatesNoPreference() {
        ApiResponse response = household.owner().get("/api/reports/income-and-spending" + QUERY);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).get().asString().startsWith("application/json");
    }

    @Test
    void answersJsonToWhatAnAngularHttpClientSends() {
        ApiResponse response =
                household
                        .owner()
                        .getAccepting(
                                "/api/reports/spending-by-category" + QUERY,
                                "application/json, text/plain, */*");

        assertThat(response.header("Content-Type")).get().asString().startsWith("application/json");
    }

    @Test
    void answersJsonToAnAcceptEverything() {
        ApiResponse response =
                household.owner().getAccepting("/api/reports/income-and-spending" + QUERY, "*/*");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).get().asString().startsWith("application/json");
    }

    @Test
    void aTopLevelNavigationDoesNotDownloadTheFile() {
        // What a hostile page can cause: a navigation carrying the SameSite=Lax cookie. It sends
        // Accept: text/html, which neither representation serves.
        ApiResponse response =
                household
                        .owner()
                        .getAccepting(
                                "/api/reports/income-and-spending" + QUERY,
                                "text/html,application/xhtml+xml");

        assertThat(response.status()).isEqualTo(406);
        assertThat(response.body()).doesNotContain("2400");
    }
}
