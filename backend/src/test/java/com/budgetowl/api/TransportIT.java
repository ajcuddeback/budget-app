package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Two credential transports, one API (ADR-0018) — and the single difference between them.
 *
 * <p>An endpoint that authorizes correctly for the web and not for mobile is a real and easy bug,
 * so the parity tests here compare the actual bytes rather than eyeballing two similar
 * implementations. The one legitimate difference is CSRF: a cookie is sent by the browser whether
 * or not the user meant it, and a bearer token is not, so only the cookie transport needs a token
 * proving the request was intended.
 */
class TransportIT extends ApiTestBase {

    private static final String SESSION_COOKIE = "BUDGETOWL_SESSION";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    // -------------------------------------------------------------------------------- CSRF

    @Test
    void refusesAStateChangingSessionRequestWithNoCsrfToken() {
        Household household = seedHousehold();
        ApiClient withoutToken =
                anonymous()
                        .withCookie(
                                SESSION_COOKIE,
                                household.owner().cookie(SESSION_COOKIE).orElseThrow())
                        .withoutCsrfToken();

        ApiResponse response =
                withoutToken.post(
                        "/api/households/current/invitations",
                        "{\"email\":\"accomplice@example.com\",\"role\":\"OWNER\"}");

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.stringField("code")).isEqualTo("csrf-token-required");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM household_invitations WHERE email ="
                                        + " CAST('accomplice@example.com' AS citext)",
                                Long.class))
                .isZero();
    }

    @Test
    void refusesAStateChangingSessionRequestWithTheWrongCsrfToken() {
        Household household = seedHousehold();
        ApiClient owner = household.owner().withCsrfToken("a-token-from-somewhere-else");

        ApiResponse response =
                owner.post(
                        "/api/households/current/invitations",
                        "{\"email\":\"accomplice@example.com\",\"role\":\"OWNER\"}");

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.stringField("code")).isEqualTo("csrf-token-required");
    }

    @Test
    void issuesACsrfTokenToAnyCallerSoALoginIsPossibleAtAll() {
        ApiClient browser = browser();

        assertThat(browser.cookie(CSRF_COOKIE))
                .as("a client that cannot obtain a token cannot make any state-changing request")
                .isPresent();
    }

    @Test
    void refusesALoginWithNoCsrfTokenFromABrowser() {
        createFirstUser();

        ApiResponse response =
                anonymous()
                        .withoutCsrfToken()
                        .post(
                                "/api/auth/login",
                                """
                                {"email":"%s","password":"%s"}
                                """
                                        .formatted(OWNER_EMAIL, OWNER_SECRET));

        assertThat(response.status())
                .as("login CSRF signs a victim into the attacker's account; the token is required")
                .isEqualTo(403);
    }

    @Test
    void asksNoCsrfTokenOfTheBearerTransport() {
        seedHousehold();

        ApiClient phone =
                anonymous()
                        .withBearerToken(issueToken(OWNER_EMAIL, OWNER_SECRET, "Ada's phone"))
                        .withoutCsrfToken();
        ApiResponse response =
                phone.post(
                        "/api/households/current/invitations",
                        "{\"email\":\"chris@example.com\",\"role\":\"MEMBER\"}");

        assertThat(response.status())
                .as("a bearer request carries no ambient credential for a hostile page to ride on")
                .isEqualTo(201);
    }

    @Test
    void asksNoCsrfTokenToIssueABearerTokenInTheFirstPlace() {
        createFirstUser();

        ApiResponse response =
                anonymous()
                        .withoutCsrfToken()
                        .post(
                                "/api/auth/token",
                                """
                                {"email":"%s","password":"%s","deviceLabel":"Ada's phone"}
                                """
                                        .formatted(OWNER_EMAIL, OWNER_SECRET));

        assertThat(response.status())
                .as("a cookie-less client has no way to fetch a CSRF token first")
                .isEqualTo(201);
    }

    // ------------------------------------------------------------------------------ parity

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/auth/me",
                "/api/households/current",
                "/api/households/current/members"
            })
    void answersIdenticallyOnBothTransportsForAnOwner(String path) {
        seedHousehold();
        ApiClient browser = signIn(OWNER_EMAIL, OWNER_SECRET);
        ApiClient phone = mobile(OWNER_EMAIL, OWNER_SECRET, "Ada's phone");

        ApiResponse overSession = browser.get(path);
        ApiResponse overBearer = phone.get(path);

        assertThat(overBearer.status()).isEqualTo(overSession.status());
        assertThat(overBearer.body()).isEqualTo(overSession.body());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/auth/me",
                "/api/households/current",
                "/api/households/current/members"
            })
    void answersIdenticallyOnBothTransportsForAViewer(String path) {
        seedHousehold();
        ApiClient browser = signIn(VIEWER_EMAIL, VIEWER_SECRET);
        ApiClient phone = mobile(VIEWER_EMAIL, VIEWER_SECRET, "Alan's phone");

        ApiResponse overSession = browser.get(path);
        ApiResponse overBearer = phone.get(path);

        assertThat(overBearer.status()).isEqualTo(overSession.status());
        assertThat(overBearer.body()).isEqualTo(overSession.body());
    }

    @Test
    void refusesAViewersWriteOnBothTransportsAlike() {
        seedHousehold();
        ApiClient browser = signIn(VIEWER_EMAIL, VIEWER_SECRET);
        ApiClient phone = mobile(VIEWER_EMAIL, VIEWER_SECRET, "Alan's phone");
        String body = "{\"email\":\"accomplice@example.com\",\"role\":\"OWNER\"}";

        ApiResponse overSession = browser.post("/api/households/current/invitations", body);
        ApiResponse overBearer = phone.post("/api/households/current/invitations", body);

        assertThat(overSession.status()).isEqualTo(403);
        assertThat(overBearer.status()).isEqualTo(403);
        assertThat(overBearer.bodyWithoutCorrelationId())
                .isEqualTo(overSession.bodyWithoutCorrelationId());
    }

    // ------------------------------------------------------------------- cookie and headers

    @Test
    void givesTheBrowserASessionCookieABrowserCanDefend() {
        createFirstUser();
        ApiClient browser = browser();

        ApiResponse response =
                browser.post(
                        "/api/auth/login",
                        """
                        {"email":"%s","password":"%s"}
                        """
                                .formatted(OWNER_EMAIL, OWNER_SECRET));

        String cookie =
                response.headerValues("set-cookie").stream()
                        .filter(header -> header.startsWith(SESSION_COOKIE + "="))
                        .findFirst()
                        .orElseThrow();
        assertThat(cookie).contains("HttpOnly").contains("Secure").contains("SameSite=Lax");
        assertThat(cookie).contains("Path=/");
    }

    @Test
    void setsTheSecurityHeadersOnEveryResponse() {
        List<String> headers =
                List.of(
                        "Content-Security-Policy",
                        "Referrer-Policy",
                        "X-Content-Type-Options",
                        "X-Frame-Options",
                        "Cache-Control");

        ApiResponse response = anonymous().get("/api/setup/status");

        for (String header : headers) {
            assertThat(response.header(header)).as(header).isPresent();
        }
        assertThat(response.header("X-Frame-Options")).contains("DENY");
        assertThat(response.header("Cache-Control")).get().asString().contains("no-store");
        assertThat(response.header("Referrer-Policy")).contains("no-referrer");
    }
}
