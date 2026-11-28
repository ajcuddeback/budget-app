package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The admin console over real HTTP against real PostgreSQL.
 *
 * <p>Two of these are the whole point of the slice:
 *
 * <ul>
 *   <li><b>Administrator, not {@code OWNER}.</b> ADR-0029 makes them different axes, and the one
 *       way to prove it is to point a household {@code OWNER} at every route and watch it refuse.
 *       The owner here is also the administrator, so the test uses a <em>second</em> owner —
 *       otherwise it would be asserting against a person who passes for the wrong reason.
 *   <li><b>The OIDC client secret goes in and never comes out.</b> Asserted against the response
 *       <em>text</em>, not against a field, because a DTO with no field for it proves nothing about
 *       what serialisation, an error body or a stack trace might produce.
 * </ul>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AdminIT extends ApiTestBase {

    private static final String SECRET = "oidc-client-secret-nobody-should-see";
    private static final String ISSUER = "https://login.example.com";

    @Test
    void the_administrator_is_the_person_who_completed_setup() {
        Household household = seedHousehold();

        ApiResponse instance = household.owner().get("/api/admin/instance");

        assertThat(instance.status()).isEqualTo(200);
        assertThat(instance.stringField("administratorEmail")).isEqualTo(OWNER_EMAIL);
        // Three accounts were invited into this household; the console counts who can sign in.
        assertThat(instance.numberField("accountCount")).isEqualTo(3);
        assertThat(instance.stringField("setupCompletedAt")).isNotBlank();
    }

    @Test
    void the_console_holds_no_financial_data() {
        Household household = seedHousehold();
        createAccount(household.owner(), "Current", "4321");

        // Not a balance, not a transaction, not a count of them. ADR-0026 says the operator can
        // read the database if they choose; this is the reason not to build the screen that does
        // it for them, and the reason a VIEWER made administrator would gain no view of the money.
        for (String path :
                List.of("/api/admin/instance", "/api/admin/settings", "/api/admin/users")) {
            assertThat(household.owner().get(path).body())
                    .as(path)
                    .doesNotContain("balance")
                    .doesNotContain("Current")
                    .doesNotContain("4321");
        }
    }

    @Test
    void a_household_owner_who_is_not_the_administrator_is_refused_everywhere() {
        Household household = seedHousehold();
        // A SECOND owner. The seeded owner is also the administrator, so pointing this at them
        // would pass for the wrong reason and the test would prove nothing about the two axes.
        acceptInvitation(
                invite(household.owner(), "second@example.com", "OWNER"),
                "Bea",
                "bea-example-passphrase");
        ApiClient secondOwner = signIn("second@example.com", "bea-example-passphrase");

        for (Endpoint endpoint : everyRoute()) {
            ApiResponse response = endpoint.callWith(secondOwner);
            assertThat(response.status()).as(endpoint.toString()).isEqualTo(403);
            // Not owner-only, and not not-found: the instance's existence is no secret from the
            // people signed in to it.
            assertThat(response.stringField("code")).isEqualTo("not-instance-admin");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRoute")
    void a_member_and_a_viewer_are_refused_too(Endpoint endpoint) {
        Household household = seedHousehold();

        assertThat(endpoint.callWith(household.member()).status()).isEqualTo(403);
        assertThat(endpoint.callWith(household.viewer()).status()).isEqualTo(403);
    }

    @Test
    void the_oidc_client_secret_goes_in_and_never_comes_out() {
        Household household = seedHousehold();
        long version = household.owner().get("/api/admin/settings").numberField("version");

        ApiResponse saved = configureOidc(household, version);
        assertThat(saved.status()).isEqualTo(200);

        // Against the TEXT of every response, not against a field: a DTO with no field for it
        // proves nothing about what serialisation or an error body might produce.
        assertThat(saved.body()).doesNotContain(SECRET);
        assertThat(household.owner().get("/api/admin/settings").body()).doesNotContain(SECRET);
        assertThat(household.owner().get("/api/admin/instance").body()).doesNotContain(SECRET);

        // What a surface may know: that one is set.
        ApiResponse read = household.owner().get("/api/admin/settings");
        assertThat(read.body()).contains("\"oidcClientSecretSet\":true");
        assertThat(read.stringField("oidcIssuerUri")).isEqualTo(ISSUER);
    }

    @Test
    void password_login_cannot_be_turned_off_until_another_route_is_proven() {
        Household household = seedHousehold();
        long version = household.owner().get("/api/admin/settings").numberField("version");
        configureOidc(household, version);

        ApiResponse refused =
                household
                        .owner()
                        .patch(
                                "/api/admin/settings",
                                "{\"passwordLoginEnabled\":false,\"version\":"
                                        + household
                                                .owner()
                                                .get("/api/admin/settings")
                                                .numberField("version")
                                        + "}");

        // "Disable password login, then discover the OIDC config is wrong" locks the operator out
        // of their own records on their own hardware, with no support desk to call.
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.stringField("code")).isEqualTo("password-login-locked");
        assertThat(household.owner().get("/api/admin/settings").body())
                .contains("\"passwordLoginEnabled\":true")
                .contains("\"passwordLoginLocked\":true");
    }

    @Test
    void the_console_says_the_switch_is_locked_before_anybody_tries_it() {
        Household household = seedHousehold();

        // The console states the rule beside the switch rather than offering a control that
        // fails, so the flag has to be readable without attempting the change.
        assertThat(household.owner().get("/api/admin/settings").body())
                .contains("\"passwordLoginLocked\":true");
    }

    @Test
    void turning_oidc_off_brings_password_login_back_with_it() {
        Household household = seedHousehold();
        configureOidc(
                household, household.owner().get("/api/admin/settings").numberField("version"));

        ApiResponse off =
                household
                        .owner()
                        .patch(
                                "/api/admin/settings",
                                "{\"oidcEnabled\":false,\"version\":"
                                        + household
                                                .owner()
                                                .get("/api/admin/settings")
                                                .numberField("version")
                                        + "}");

        // Turning off the only login route is how somebody locks themselves out of their own
        // server. One route always remains.
        assertThat(off.status()).isEqualTo(200);
        assertThat(off.body())
                .contains("\"oidcEnabled\":false")
                .contains("\"passwordLoginEnabled\":true")
                .contains("\"oidcProvisioningEnabled\":false");
    }

    @Test
    void an_incomplete_oidc_configuration_is_refused_by_name() {
        Household household = seedHousehold();
        long version = household.owner().get("/api/admin/settings").numberField("version");

        ApiResponse refused =
                household
                        .owner()
                        .patch(
                                "/api/admin/settings",
                                "{\"oidcEnabled\":true,\"oidcIssuerUri\":\""
                                        + ISSUER
                                        + "\","
                                        + "\"version\":"
                                        + version
                                        + "}");

        // A provider switched on with no client id is a broken login route. The administrator is
        // told which field is missing rather than shown a constraint violation.
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("oidcClientId");
    }

    @Test
    void clearing_the_client_secret_leaves_a_provider_that_cannot_be_used() {
        Household household = seedHousehold();
        configureOidc(
                household, household.owner().get("/api/admin/settings").numberField("version"));

        ApiResponse cleared =
                household
                        .owner()
                        .patch(
                                "/api/admin/settings",
                                "{\"oidcClientSecret\":null,\"version\":"
                                        + household
                                                .owner()
                                                .get("/api/admin/settings")
                                                .numberField("version")
                                        + "}");

        // Clearing it is allowed — it is how somebody retires a leaked secret — but an OIDC
        // configuration with no secret is a broken login route, so it is refused by name rather
        // than saved and discovered at the next sign-in.
        assertThat(cleared.status()).isEqualTo(400);
        assertThat(cleared.body()).contains("oidcClientSecret");
        assertThat(household.owner().get("/api/admin/settings").body())
                .as("and the stored configuration is untouched")
                .contains("\"oidcClientSecretSet\":true");
    }

    @Test
    void a_provider_with_no_issuer_is_refused_by_name() {
        Household household = seedHousehold();
        long version = household.owner().get("/api/admin/settings").numberField("version");

        ApiResponse refused =
                household
                        .owner()
                        .patch(
                                "/api/admin/settings",
                                "{\"oidcEnabled\":true,\"oidcClientId\":\"budget-owl\","
                                        + "\"oidcClientSecret\":\""
                                        + SECRET
                                        + "\",\"version\":"
                                        + version
                                        + "}");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("oidcIssuerUri");
    }

    @Test
    void provisioning_can_be_turned_on_only_once_a_provider_exists() {
        Household household = seedHousehold();
        configureOidc(
                household, household.owner().get("/api/admin/settings").numberField("version"));

        ApiResponse on =
                household
                        .owner()
                        .patch(
                                "/api/admin/settings",
                                "{\"oidcProvisioningEnabled\":true,\"version\":"
                                        + household
                                                .owner()
                                                .get("/api/admin/settings")
                                                .numberField("version")
                                        + "}");

        // An OIDC login may provision a USER; it never grants household membership (ADR-0017).
        // Off by default, because otherwise the provider's whole directory lands inside
        // somebody's finances.
        assertThat(on.status()).isEqualTo(200);
        assertThat(on.body()).contains("\"oidcProvisioningEnabled\":true");
    }

    @Test
    void changing_the_issuer_withdraws_the_evidence_that_the_old_one_worked() {
        Household household = seedHousehold();
        configureOidc(
                household, household.owner().get("/api/admin/settings").numberField("version"));
        // Stand in for an OWNER having signed in through the provider, which is the only thing
        // that unlocks the password-login switch.
        jdbc.update("UPDATE instance_settings SET oidc_owner_login_at = now() WHERE id = 1");
        assertThat(household.owner().get("/api/admin/settings").body())
                .contains("\"passwordLoginLocked\":false");

        household
                .owner()
                .patch(
                        "/api/admin/settings",
                        "{\"oidcIssuerUri\":\"https://elsewhere.example.com\",\"version\":"
                                + household
                                        .owner()
                                        .get("/api/admin/settings")
                                        .numberField("version")
                                + "}");

        // That timestamp attested that THIS provider can sign an owner in. Pointed at a different
        // one, it attests to nothing, and the switch locks again.
        assertThat(household.owner().get("/api/admin/settings").body())
                .contains("\"passwordLoginLocked\":true")
                .contains("\"passwordLoginEnabled\":true");
    }

    @Test
    void replacing_the_client_secret_withdraws_the_evidence_too() {
        Household household = seedHousehold();
        configureOidc(
                household, household.owner().get("/api/admin/settings").numberField("version"));
        jdbc.update("UPDATE instance_settings SET oidc_owner_login_at = now() WHERE id = 1");
        // The legal, reachable starting state this test is about: OIDC proven, password login
        // hidden behind it.
        hidePasswordLogin(household);
        assertThat(household.owner().get("/api/admin/settings").body())
                .contains("\"passwordLoginEnabled\":false");

        household
                .owner()
                .patch(
                        "/api/admin/settings",
                        "{\"oidcClientSecret\":\"a-mistyped-secret\",\"version\":"
                                + household
                                        .owner()
                                        .get("/api/admin/settings")
                                        .numberField("version")
                                + "}");

        // The secret is half the client credential, so replacing it is replacing the credential.
        // Left out of the comparison, this request kept the proof AND kept password login hidden
        // — and since the new secret is wrong, the token exchange now fails for everybody. That is
        // a complete lockout reached by one routine-looking PATCH, recoverable only from the host
        // shell. The constraint cannot catch it: all it can see is that OIDC is on and that
        // somebody once signed in.
        assertThat(household.owner().get("/api/admin/settings").body())
                .contains("\"passwordLoginEnabled\":true")
                .contains("\"passwordLoginLocked\":true");
    }

    @Test
    void the_version_in_a_response_is_the_one_the_next_change_needs() {
        Household household = seedHousehold();
        long version = household.owner().get("/api/admin/settings").numberField("version");

        ApiResponse first =
                household
                        .owner()
                        .patch(
                                "/api/admin/settings",
                                "{\"registrationOpen\":true,\"version\":" + version + "}");
        assertThat(first.status()).isEqualTo(200);

        // Straight back in with the version the PATCH returned, with no GET in between — which is
        // what a console doing read-modify-write actually does. Saved without a flush, @Version is
        // still incremented at commit, so this response carries the version the caller already
        // had and this second request is refused as stale: the lock V16 added, useless in the one
        // loop it was added for.
        ApiResponse second =
                household
                        .owner()
                        .patch(
                                "/api/admin/settings",
                                "{\"registrationOpen\":false,\"version\":"
                                        + first.numberField("version")
                                        + "}");

        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body()).contains("\"registrationOpen\":false");
    }

    @Test
    void a_blank_issuer_is_a_named_validation_error_not_a_constraint_violation() {
        Household household = seedHousehold();
        configureOidc(
                household, household.owner().get("/api/admin/settings").numberField("version"));

        ApiResponse blank =
                household
                        .owner()
                        .patch(
                                "/api/admin/settings",
                                "{\"oidcIssuerUri\":\"   \",\"version\":"
                                        + household
                                                .owner()
                                                .get("/api/admin/settings")
                                                .numberField("version")
                                        + "}");

        // Not null, so the service's null test waves it through; blank, so
        // ck_instance_settings_oidc_configured refuses it at flush and the handler answers 409
        // with nothing to act on. The administrator should be told which box to fill in.
        assertThat(blank.status()).isEqualTo(400);
        assertThat(blank.body()).contains("\"field\":\"oidcIssuerUri\"");

        // And nothing was written: the provider that was working still is.
        assertThat(household.owner().get("/api/admin/settings").body()).contains(ISSUER);
    }

    @Test
    void registration_can_be_opened_and_closed() {
        Household household = seedHousehold();
        long version = household.owner().get("/api/admin/settings").numberField("version");

        ApiResponse opened =
                household
                        .owner()
                        .patch(
                                "/api/admin/settings",
                                "{\"registrationOpen\":true,\"version\":" + version + "}");

        assertThat(opened.body()).contains("\"registrationOpen\":true");
        // And the public setup endpoint agrees, because that is the thing the switch governs.
        assertThat(anonymous().get("/api/setup/status").body())
                .contains("\"registrationOpen\":true");
    }

    @Test
    void an_edit_from_a_stale_copy_is_refused() {
        Household household = seedHousehold();
        long stale = household.owner().get("/api/admin/settings").numberField("version");
        household
                .owner()
                .patch(
                        "/api/admin/settings",
                        "{\"registrationOpen\":true,\"version\":" + stale + "}");

        ApiResponse second =
                household
                        .owner()
                        .patch(
                                "/api/admin/settings",
                                "{\"registrationOpen\":false,\"version\":" + stale + "}");

        // One row and one administrator still means two browser tabs, and the field being lost
        // here is how everybody signs in.
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.stringField("code")).isEqualTo("stale-version");
        assertThat(household.owner().get("/api/admin/settings").body())
                .contains("\"registrationOpen\":true");
    }

    @Test
    void the_accounts_list_names_who_can_sign_in_and_who_is_the_administrator() {
        Household household = seedHousehold();

        ApiResponse accounts = household.owner().get("/api/admin/users");

        assertThat(accounts.numberField("total")).isEqualTo(3);
        assertThat(accounts.body())
                .contains(OWNER_EMAIL)
                .contains(MEMBER_EMAIL)
                .contains(VIEWER_EMAIL);
        // Exactly one, by ADR-0029 and by a unique partial index.
        assertThat(accounts.body().split("\"instanceAdmin\":true", -1)).hasSize(2);
        // And no password hash, because UserSummary has no field for one.
        assertThat(accounts.body()).doesNotContain("passwordHash").doesNotContain("$2");
    }

    @Test
    void an_anonymous_caller_learns_nothing_about_these_routes() {
        for (Endpoint endpoint : everyRoute()) {
            ApiResponse response = endpoint.callWith(anonymous());

            // 401 for a read, and 403 csrf-token-required for the write — a session-less browser
            // has no CSRF token, and that check runs first. Either way the caller is turned away
            // before anything reads the administrator flag, which is the property that matters:
            // whether a route is administrator-only is not something to be learned by probing.
            assertThat(response.status()).as(endpoint.toString()).isIn(401, 403);
            assertThat(response.stringField("code"))
                    .as(endpoint.toString())
                    .isNotEqualTo("not-instance-admin");
        }
    }

    // ------------------------------------------------------------------------------- internals

    /** A list rather than a sample — adding a route without adding it here is the failure mode. */
    static List<Endpoint> everyRoute() {
        return List.of(
                new Endpoint("GET", "/api/admin/instance", null),
                new Endpoint("GET", "/api/admin/settings", null),
                new Endpoint(
                        "PATCH",
                        "/api/admin/settings",
                        "{\"registrationOpen\":true,\"version\":0}"),
                new Endpoint("GET", "/api/admin/users", null));
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

    /** Takes the instance to the state the lockout rule guards: OIDC proven, passwords hidden. */
    private void hidePasswordLogin(Household household) {
        ApiResponse hidden =
                household
                        .owner()
                        .patch(
                                "/api/admin/settings",
                                "{\"passwordLoginEnabled\":false,\"version\":"
                                        + household
                                                .owner()
                                                .get("/api/admin/settings")
                                                .numberField("version")
                                        + "}");
        if (hidden.status() != 200) {
            throw new AssertionError("could not hide password login: " + hidden.body());
        }
    }

    private ApiResponse configureOidc(Household household, long version) {
        return household
                .owner()
                .patch(
                        "/api/admin/settings",
                        """
                        {"oidcEnabled":true,"oidcIssuerUri":"%s","oidcClientId":"budget-owl",
                         "oidcClientSecret":"%s","version":%d}
                        """
                                .formatted(ISSUER, SECRET, version));
    }
}
