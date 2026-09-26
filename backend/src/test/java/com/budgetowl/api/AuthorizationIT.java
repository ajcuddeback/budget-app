package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Who may call what.
 *
 * <p>Since ADR-0026 there is one household per instance, so there is no second household to leak to
 * and the cross-household test that used to be the highest-value one in the codebase has nothing
 * left to prove. <b>The risk did not go away with it</b> — it moved onto authentication and role
 * enforcement, which is why the two lists below are enumerated rather than sampled. Every endpoint
 * appears in them; adding an endpoint without adding it here is the failure mode these tests exist
 * to catch.
 */
class AuthorizationIT extends ApiTestBase {

    /**
     * Every non-public endpoint, by method and path. Not a sample: a list, which a new endpoint has
     * to be added to.
     */
    static List<Endpoint> protectedEndpoints() {
        UUID any = UUID.fromString("00000000-0000-4000-8000-00000000f00d");
        return List.of(
                new Endpoint("POST", "/api/auth/logout", null),
                new Endpoint("GET", "/api/auth/me", null),
                new Endpoint("GET", "/api/auth/devices", null),
                new Endpoint("DELETE", "/api/auth/devices/t-" + any, null),
                new Endpoint(
                        "POST",
                        "/api/auth/password",
                        "{\"currentPassword\":\"a-example-one\",\"newPassword\":\"another-example-one\"}"),
                new Endpoint("GET", "/api/households/current", null),
                new Endpoint(
                        "PUT",
                        "/api/households/current",
                        "{\"name\":\"Home\",\"baseCurrency\":\"GBP\"}"),
                new Endpoint("GET", "/api/households/current/members", null),
                new Endpoint(
                        "PATCH", "/api/households/current/members/" + any, "{\"role\":\"OWNER\"}"),
                new Endpoint(
                        "PATCH",
                        "/api/households/current/members/me",
                        "{\"displayCurrency\":\"EUR\"}"),
                new Endpoint("DELETE", "/api/households/current/members/" + any, null),
                new Endpoint(
                        "POST",
                        "/api/households/current/invitations",
                        "{\"email\":\"x@example.com\",\"role\":\"MEMBER\"}"),
                new Endpoint("DELETE", "/api/households/current/invitations/" + any, null));
    }

    /** The five exceptions, listed here so that a sixth cannot appear unnoticed. */
    static List<Endpoint> publicEndpoints() {
        return List.of(
                new Endpoint("GET", "/api/setup/status", null),
                new Endpoint("POST", "/api/setup/first-user", "{}"),
                new Endpoint("POST", "/api/auth/login", "{}"),
                new Endpoint("POST", "/api/auth/token", "{}"),
                new Endpoint("POST", "/api/invitations/nothing/accept", "{}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedEndpoints")
    void answers401ToAnUnauthenticatedBrowser(Endpoint endpoint) {
        seedHousehold();

        // A browser that has a CSRF token but no session: the request reaches the authorization
        // check rather than being turned away earlier for a different reason.
        ApiResponse response = endpoint.callWith(browser());

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.stringField("code")).isEqualTo("not-authenticated");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedEndpoints")
    void answers401ToAnUnknownBearerToken(Endpoint endpoint) {
        seedHousehold();

        ApiResponse response =
                endpoint.callWith(anonymous().withBearerToken("not-a-token-anybody-issued"));

        assertThat(response.status()).isEqualTo(401);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("publicEndpoints")
    void doesNotDemandAuthenticationOnThePublicRoutes(Endpoint endpoint) {
        ApiResponse response = endpoint.callWith(browser());

        assertThat(response.status())
                .as("a public route may refuse the request, but never for want of credentials")
                .isNotIn(401, 403);
    }

    // ------------------------------------------------------------------------------- roles

    /** Every endpoint that changes household state. A VIEWER may read and never write. */
    static List<Endpoint> householdWrites() {
        UUID any = UUID.fromString("00000000-0000-4000-8000-00000000f00d");
        return List.of(
                new Endpoint(
                        "PUT",
                        "/api/households/current",
                        "{\"name\":\"Renamed\",\"baseCurrency\":\"EUR\"}"),
                new Endpoint(
                        "PATCH",
                        "/api/households/current/members/{member}",
                        "{\"role\":\"OWNER\"}"),
                new Endpoint("DELETE", "/api/households/current/members/{member}", null),
                new Endpoint(
                        "POST",
                        "/api/households/current/invitations",
                        "{\"email\":\"x@example.com\",\"role\":\"MEMBER\"}"),
                new Endpoint("DELETE", "/api/households/current/invitations/" + any, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("householdWrites")
    void answers403ToAViewerOnEveryWrite(Endpoint endpoint) {
        Household household = seedHousehold();

        ApiResponse response = call(household.viewer(), endpoint);

        assertThat(response.status()).isEqualTo(403);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("householdWrites")
    void answers403ToAMemberOnEveryOwnerOnlyEndpoint(Endpoint endpoint) {
        Household household = seedHousehold();

        ApiResponse response = call(household.member(), endpoint);

        assertThat(response.status()).as("every one of these is an owner's to do").isEqualTo(403);
        assertThat(response.stringField("code")).isIn("owner-only", "read-only-role");
    }

    @Test
    void letsAViewerReadTheHouseholdAndItsMembers() {
        Household household = seedHousehold();

        assertThat(household.viewer().get("/api/households/current").status()).isEqualTo(200);
        assertThat(household.viewer().get("/api/households/current/members").status())
                .isEqualTo(200);
    }

    @Test
    void letsAViewerChangeTheirOwnDisplayPreferences() {
        // Self-scoped and display-only (ADR-0022, ADR-0023): it changes what this member sees and
        // never what is recorded, so it is not one of the writes a VIEWER is refused.
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .viewer()
                        .patch(
                                "/api/households/current/members/me",
                                "{\"displayCurrency\":\"EUR\",\"locale\":\"cy-GB\"}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.stringField("displayCurrency")).isEqualTo("EUR");
    }

    @Test
    void refusesAnOwnerChangingTheirOwnRole() {
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .owner()
                        .patch(
                                "/api/households/current/members/" + membershipIdOf(OWNER_EMAIL),
                                "{\"role\":\"MEMBER\"}");

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.stringField("code")).isEqualTo("own-role-unchangeable");
        assertThat(roleOf(OWNER_EMAIL)).isEqualTo("OWNER");
    }

    @Test
    void refusesAMemberPromotingThemselves() {
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .member()
                        .patch(
                                "/api/households/current/members/" + membershipIdOf(MEMBER_EMAIL),
                                "{\"role\":\"OWNER\"}");

        assertThat(response.status()).isEqualTo(403);
        assertThat(roleOf(MEMBER_EMAIL)).isEqualTo("MEMBER");
    }

    @Test
    void letsAnOwnerChangeSomebodyElsesRole() {
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .owner()
                        .patch(
                                "/api/households/current/members/" + membershipIdOf(VIEWER_EMAIL),
                                "{\"role\":\"MEMBER\"}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(roleOf(VIEWER_EMAIL)).isEqualTo("MEMBER");
    }

    @Test
    void answers404ForAMembershipThatDoesNotExist() {
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .owner()
                        .patch(
                                "/api/households/current/members/"
                                        + UUID.fromString("00000000-0000-4000-8000-00000000dead"),
                                "{\"role\":\"MEMBER\"}");

        assertThat(response.status()).isEqualTo(404);
    }

    // ------------------------------------------------------- a signed-in user with no household

    @Test
    void answers403NotACrashForASignedInUserWithNoMembership() {
        ApiClient outsider = signInWithoutAMembership();

        ApiResponse response = outsider.get("/api/households/current");

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.stringField("code")).isEqualTo("not-a-member");
    }

    @Test
    void signsInAUserWithNoMembershipToAnEmptyStateRatherThanAnError() {
        ApiClient outsider = signInWithoutAMembership();

        ApiResponse response = outsider.get("/api/auth/me");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("\"household\":null");
    }

    /** Invited, joined, removed — and still a user, which is the state ADR-0017 describes. */
    private ApiClient signInWithoutAMembership() {
        Household household = seedHousehold();
        acceptInvitation(
                invite(household.owner(), OUTSIDER_EMAIL, "MEMBER"),
                "Edsger",
                "edsger-example-passphrase");
        household
                .owner()
                .delete("/api/households/current/members/" + membershipIdOf(OUTSIDER_EMAIL));
        return signIn(OUTSIDER_EMAIL, "edsger-example-passphrase");
    }

    private String roleOf(String email) {
        return jdbc.queryForObject(
                """
                SELECT m.role FROM household_members m JOIN users u ON u.id = m.user_id
                 WHERE u.email = CAST(? AS citext)
                """,
                String.class,
                email);
    }

    /** One call, so the lists above read as a table rather than as five overloads. */
    /**
     * Resolves {@code {member}} to a membership that really exists, so a refusal is a refusal
     * rather than a 404 for an id that was never there — the two are different answers to different
     * questions, and only one of them is what these tests are about.
     */
    private ApiResponse call(ApiClient client, Endpoint endpoint) {
        String path = endpoint.path().replace("{member}", membershipIdOf(OWNER_EMAIL).toString());
        return client.send(endpoint.method(), path, endpoint.body());
    }

    record Endpoint(String method, String path, String body) {

        ApiResponse callWith(ApiClient client) {
            return client.send(method, path, body);
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }
}
