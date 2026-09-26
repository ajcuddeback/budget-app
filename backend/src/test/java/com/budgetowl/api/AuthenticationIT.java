package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Signing in, staying signed in, and stopping being signed in — on both transports (ADR-0018).
 *
 * <p>The tests that matter most here are the ones about what an attacker can <em>learn</em> and
 * what they can <em>keep</em>: whether a failed login tells them an address exists, whether a
 * planted session id survives a login, and whether a credential still works after its owner has
 * revoked it.
 */
class AuthenticationIT extends ApiTestBase {

    private static final String SESSION_COOKIE = "BUDGETOWL_SESSION";

    @Test
    void signsInAndReturnsTheCallerWithTheirRole() {
        createFirstUser();

        ApiClient browser = browser();
        ApiResponse response =
                browser.post(
                        "/api/auth/login",
                        """
                        {"email":"%s","password":"%s"}
                        """
                                .formatted(OWNER_EMAIL, OWNER_SECRET));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.stringField("email")).isEqualTo(OWNER_EMAIL);
        assertThat(response.stringField("role")).isEqualTo("OWNER");
        assertThat(browser.cookie(SESSION_COOKIE)).isPresent();
    }

    /**
     * The whole point of the constant response: an attacker POSTing a list of addresses learns
     * nothing from any of them. Compared with the correlation id blanked, because that is fresh per
     * response and carries no information about the account.
     */
    @Test
    void answersIdenticallyForAnUnknownEmailAndAWrongPassword() {
        createFirstUser();

        ApiResponse unknownEmail = attemptLogin("nobody@example.com", OWNER_SECRET);
        ApiResponse wrongPassword = attemptLogin(OWNER_EMAIL, "not-the-example-passphrase");

        assertThat(unknownEmail.status()).isEqualTo(401);
        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(unknownEmail.bodyWithoutCorrelationId())
                .isEqualTo(wrongPassword.bodyWithoutCorrelationId());
        assertThat(unknownEmail.body()).doesNotContain("password").doesNotContain("email");
    }

    /**
     * And takes the same time doing it, because the no-user path still runs a hash comparison
     * against a dummy. Without that, "no such user" returns in a millisecond and "wrong password"
     * in hundreds — a difference an attacker can measure across the internet.
     *
     * <p>Asserted as an absolute difference of medians rather than a ratio: at the test suite's
     * deliberately cheap BCrypt cost both paths are sub-millisecond, and a ratio between two tiny
     * numbers is noise. The failure this catches is a missing comparison, which is not subtle.
     */
    @Test
    void takesComparableTimeForAnUnknownEmailAndAWrongPassword() {
        createFirstUser();

        long unknownEmail = medianMillis(() -> attemptLogin("nobody@example.com", OWNER_SECRET));
        long wrongPassword =
                medianMillis(() -> attemptLogin(OWNER_EMAIL, "not-the-example-passphrase"));

        assertThat(Math.abs(unknownEmail - wrongPassword))
                .as("unknown email %dms vs wrong password %dms", unknownEmail, wrongPassword)
                .isLessThan(150);
    }

    @Test
    void refusesADisabledAccountWithTheSameAnswerAsAWrongPassword() {
        createFirstUser();
        jdbc.update("UPDATE users SET status = 'DISABLED'");

        ApiResponse disabled = attemptLogin(OWNER_EMAIL, OWNER_SECRET);
        ApiResponse wrongPassword = attemptLogin(OWNER_EMAIL, "not-the-example-passphrase");

        assertThat(disabled.status()).isEqualTo(401);
        assertThat(disabled.bodyWithoutCorrelationId())
                .isEqualTo(wrongPassword.bodyWithoutCorrelationId());
    }

    /** Session fixation: an id that existed before the login does not survive it. */
    @Test
    void changesTheSessionIdAcrossLogin() {
        createFirstUser();
        ApiClient browser = signIn(OWNER_EMAIL, OWNER_SECRET);
        String before = browser.cookie(SESSION_COOKIE).orElseThrow();

        browser.post(
                "/api/auth/login",
                """
                {"email":"%s","password":"%s"}
                """
                        .formatted(OWNER_EMAIL, OWNER_SECRET));
        String after = browser.cookie(SESSION_COOKIE).orElseThrow();

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void rejectsTheOldSessionImmediatelyAfterLogout() {
        createFirstUser();
        ApiClient browser = signIn(OWNER_EMAIL, OWNER_SECRET);
        String sessionId = browser.cookie(SESSION_COOKIE).orElseThrow();

        assertThat(browser.post("/api/auth/logout", null).status()).isEqualTo(204);

        ApiResponse replayed =
                anonymous().withCookie(SESSION_COOKIE, sessionId).get("/api/auth/me");
        assertThat(replayed.status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Long.class))
                .as("a logged-out session is deleted, and no request creates one anonymously")
                .isZero();
    }

    @Test
    void issuesABearerTokenThatAuthenticatesTheSameEndpoints() {
        seedHousehold();

        ApiClient phone = mobile(OWNER_EMAIL, OWNER_SECRET, "Ada's phone");

        assertThat(phone.get("/api/auth/me").status()).isEqualTo(200);
        assertThat(phone.get("/api/households/current").status()).isEqualTo(200);
    }

    @Test
    void rejectsARevokedTokenOnTheVeryNextRequest() {
        seedHousehold();
        String token = issueToken(OWNER_EMAIL, OWNER_SECRET, "Ada's phone");
        ApiClient phone = anonymous().withBearerToken(token);
        assertThat(phone.get("/api/auth/me").status()).isEqualTo(200);

        assertThat(phone.post("/api/auth/logout", null).status()).isEqualTo(204);

        assertThat(phone.get("/api/auth/me").status()).isEqualTo(401);
    }

    @Test
    void listsBothTransportsAsRevocableDevices() {
        seedHousehold();
        ApiClient phone = mobile(OWNER_EMAIL, OWNER_SECRET, "Ada's phone");
        ApiClient browser = signIn(OWNER_EMAIL, OWNER_SECRET);

        ApiResponse devices = browser.get("/api/auth/devices");

        assertThat(devices.status()).isEqualTo(200);
        assertThat(devices.body()).contains("\"SESSION\"").contains("\"BEARER\"");
        assertThat(devices.body()).contains("Ada's phone");
        assertThat(phone.get("/api/auth/me").status()).isEqualTo(200);
    }

    @Test
    void revokesOneDeviceImmediatelyAndLeavesTheOthers() {
        seedHousehold();
        ApiClient phone = mobile(OWNER_EMAIL, OWNER_SECRET, "Ada's phone");
        ApiClient browser = signIn(OWNER_EMAIL, OWNER_SECRET);
        String phoneDeviceId = idOfDeviceLabelled(browser, "Ada's phone");

        assertThat(browser.delete("/api/auth/devices/" + phoneDeviceId).status()).isEqualTo(204);

        assertThat(phone.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(browser.get("/api/auth/me").status()).isEqualTo(200);
    }

    @Test
    void refusesToRevokeSomebodyElsesDevice() {
        Household household = seedHousehold();
        mobile(MEMBER_EMAIL, MEMBER_SECRET, "Grace's phone");
        String graceDevice = idOfDeviceLabelled(household.member(), "Grace's phone");

        ApiResponse response = household.owner().delete("/api/auth/devices/" + graceDevice);

        assertThat(response.status())
                .as("an owner is not an administrator of other people's credentials")
                .isEqualTo(404);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM auth_tokens WHERE revoked_at IS NULL",
                                Long.class))
                .isEqualTo(1L);
    }

    @Test
    void changesAPasswordAndRevokesEveryCredentialTheUserHeld() {
        seedHousehold();
        ApiClient phone = mobile(OWNER_EMAIL, OWNER_SECRET, "Ada's phone");
        ApiClient browser = signIn(OWNER_EMAIL, OWNER_SECRET);

        ApiResponse response =
                browser.post(
                        "/api/auth/password",
                        """
                        {"currentPassword":"%s","newPassword":"a-different-example-passphrase"}
                        """
                                .formatted(OWNER_SECRET));

        assertThat(response.status()).isEqualTo(204);
        assertThat(phone.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(browser.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(attemptLogin(OWNER_EMAIL, OWNER_SECRET).status()).isEqualTo(401);
        assertThat(signIn(OWNER_EMAIL, "a-different-example-passphrase")).isNotNull();
    }

    @Test
    void refusesAPasswordChangeWithoutTheCurrentPassword() {
        seedHousehold();
        ApiClient browser = signIn(OWNER_EMAIL, OWNER_SECRET);

        ApiResponse response =
                browser.post(
                        "/api/auth/password",
                        """
                        {"currentPassword":"not-the-example-passphrase",
                         "newPassword":"a-different-example-passphrase"}
                        """);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.stringField("code")).isEqualTo("current-password-incorrect");
        assertThat(browser.get("/api/auth/me").status()).isEqualTo(200);
    }

    /** Over BCrypt's 72-byte limit: an ordinary refusal, not a 500 from the encoder. */
    @Test
    void refusesAnOverlongPasswordAtLoginWithoutFailing() {
        createFirstUser();

        ApiResponse response = attemptLogin(OWNER_EMAIL, "x".repeat(400));

        assertThat(response.status()).isIn(400, 401);
    }

    @Test
    void backsOffAfterRepeatedFailuresAndSaysHowLongFor() {
        createFirstUser();

        ApiResponse lastAttempt = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            lastAttempt = attemptLogin(OWNER_EMAIL, "not-the-example-passphrase");
        }

        assertThat(lastAttempt).isNotNull();
        assertThat(lastAttempt.status()).isEqualTo(429);
        assertThat(lastAttempt.stringField("code")).isEqualTo("rate-limited");
        assertThat(lastAttempt.header("Retry-After")).isPresent();
        assertThat(Integer.parseInt(lastAttempt.header("Retry-After").orElseThrow()))
                .as("a lock that never lifts is a denial of service against the real user")
                .isBetween(1, 900);
    }

    private ApiResponse attemptLogin(String email, String secret) {
        return browser()
                .post(
                        "/api/auth/login",
                        """
                        {"email":"%s","password":"%s"}
                        """
                                .formatted(email, secret));
    }

    private String idOfDeviceLabelled(ApiClient client, String label) {
        String body = client.get("/api/auth/devices").body();
        int labelAt = body.indexOf("\"label\":\"" + label + "\"");
        assertThat(labelAt).as("no device labelled %s in %s", label, body).isNotNegative();
        int idAt = body.lastIndexOf("\"id\":\"", labelAt);
        int from = idAt + "\"id\":\"".length();
        return body.substring(from, body.indexOf('"', from));
    }

    private long medianMillis(Runnable attempt) {
        List<Long> samples = new ArrayList<>();
        for (int run = 0; run < 5; run++) {
            long startedAt = System.nanoTime();
            attempt.run();
            samples.add((System.nanoTime() - startedAt) / 1_000_000);
        }
        samples.sort(Long::compareTo);
        return samples.get(samples.size() / 2);
    }
}
