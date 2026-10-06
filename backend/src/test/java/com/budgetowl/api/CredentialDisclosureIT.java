package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Nothing that authenticates anybody leaves this application twice.
 *
 * <p>Two assertions, both made against text rather than types:
 *
 * <ul>
 *   <li><b>Responses.</b> Every body from a full journey is searched for a hash, a hash's column
 *       name, a session id and a token. Asserting on the serialized JSON is the point — a DTO that
 *       has no field for a secret proves nothing about what a misconfigured serializer, a {@code
 *       toString}, or a future {@code @JsonAnyGetter} might produce.
 *   <li><b>Logs.</b> The same values are searched for in everything logged during that journey. A
 *       credential in a log outlives the request and leaves the box in a bug report, which is how
 *       most of them are actually stolen.
 * </ul>
 */
class CredentialDisclosureIT extends ApiTestBase {

    private static final String JOINER_EMAIL = "chris@example.com";
    private static final String JOINER_SECRET = "chris-example-passphrase";

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    @AfterEach
    void stopCapturing() {
        root.detachAppender(captured);
        root.setLevel(Level.INFO);
    }

    @Test
    void neverPutsACredentialInAResponseBody() {
        captureLogs(Level.INFO);
        Journey journey = walkThrough();

        for (String body : journey.bodies()) {
            assertThat(body)
                    .doesNotContain("password_hash")
                    .doesNotContain("passwordHash")
                    .doesNotContain("token_hash")
                    .doesNotContain("tokenHash")
                    .doesNotContain(OWNER_SECRET)
                    .doesNotContain(JOINER_SECRET)
                    .doesNotContain(storedPasswordHash())
                    .doesNotContain(journey.sessionId())
                    .doesNotContain(journey.sessionCookie());
        }
    }

    @Test
    void returnsATokenExactlyOnceAndNeverAgain() {
        captureLogs(Level.INFO);
        Journey journey = walkThrough();

        List<String> echoed =
                journey.bodies().stream()
                        .filter(body -> body.contains(journey.bearerToken()))
                        .toList();
        List<String> links =
                journey.bodies().stream()
                        .filter(body -> body.contains(journey.invitationToken()))
                        .toList();

        assertThat(echoed)
                .as("the token is the response to asking for one, and nothing else")
                .hasSize(1);
        assertThat(links).as("the invitation link comes back only when it is created").hasSize(1);
    }

    /** At the level this application actually ships with. */
    @Test
    void logsNoCredentialDuringAFullSignInAndSignOut() {
        captureLogs(Level.INFO);
        Journey journey = walkThrough();

        String logged = String.join("\n", loggedLines(everything()));

        assertThat(logged).isNotBlank();
        assertThat(logged)
                .doesNotContain(journey.bearerToken())
                .doesNotContain(journey.invitationToken())
                .doesNotContain(journey.sessionId())
                .doesNotContain(journey.sessionCookie())
                .doesNotContain(OWNER_SECRET)
                .doesNotContain(JOINER_SECRET)
                .doesNotContain(storedPasswordHash())
                .doesNotContain("Bearer ")
                .doesNotContainIgnoringCase("authorization:");
    }

    /**
     * And with debug logging on, which is what an operator turns on when something is wrong — the
     * worst possible moment for a log to start collecting credentials.
     *
     * <p>One exception, and it is the framework's rather than ours: Spring MVC and Spring Security
     * log the request line at {@code DEBUG}, and the invitation-acceptance route carries its token
     * in the path ({@code POST /api/invitations/{token}/accept}). Nothing this application logs
     * contains it — asserted below against our own loggers — but an operator running at {@code
     * DEBUG}, or a reverse proxy writing an access log, will record that URL. That is a property of
     * putting a credential in a path, it is recorded in the feature doc, and it is why the shipped
     * level is INFO.
     */
    @Test
    void logsNoPasswordOrTokenEvenWithDebugLoggingOn() {
        captureLogs(Level.DEBUG);
        Journey journey = walkThrough();

        String logged = String.join("\n", loggedLines(everything()));
        String ours =
                String.join(
                        "\n",
                        loggedLines(event -> event.getLoggerName().startsWith("com.budgetowl")));

        assertThat(logged)
                .as("a request body is logged at DEBUG, and a record prints every component")
                .doesNotContain(OWNER_SECRET)
                .doesNotContain(JOINER_SECRET)
                .doesNotContain(storedPasswordHash())
                .doesNotContain(journey.bearerToken())
                .doesNotContain(journey.sessionId())
                .doesNotContain(journey.sessionCookie())
                .doesNotContain("Bearer ");
        assertThat(ours)
                .as("nothing this application logs contains an invitation token")
                .doesNotContain(journey.invitationToken());
    }

    @Test
    void logsTheAuthenticationEventsThatAreTheAuditTrail() {
        captureLogs(Level.INFO);
        walkThrough();

        String logged = String.join("\n", loggedLines(everything()));

        assertThat(logged).contains("authentication succeeded");
        assertThat(logged).contains("authentication failed");
        assertThat(logged).contains("bearer token issued");
        assertThat(logged).contains("invitation created");
    }

    /**
     * Everything a user does in one sitting: sign in, fail to sign in, take a token, invite
     * somebody, look at the household, revoke a device, sign out.
     */
    private Journey walkThrough() {
        List<String> bodies = new ArrayList<>();
        createFirstUser();

        ApiClient browser = browser();
        bodies.add(
                browser.post(
                                "/api/auth/login",
                                """
                                {"email":"%s","password":"%s"}
                                """
                                        .formatted(OWNER_EMAIL, OWNER_SECRET))
                        .body());
        bodies.add(
                browser()
                        .post(
                                "/api/auth/login",
                                """
                                {"email":"%s","password":"not-the-example-passphrase"}
                                """
                                        .formatted(OWNER_EMAIL))
                        .body());

        // Captured while it is still live: logout clears the cookie, and the value has to be
        // searched for in bodies and logs either way.
        String sessionCookie = browser.cookies().getOrDefault("BUDGETOWL_SESSION", "");
        String bearerToken = issueTokenCapturing(bodies);
        ApiClient phone = anonymous().withBearerToken(bearerToken);

        ApiResponse invitation =
                browser.post(
                        "/api/households/current/invitations",
                        """
                        {"email":"%s","role":"MEMBER"}
                        """
                                .formatted(JOINER_EMAIL));
        bodies.add(invitation.body());
        String invitationToken = invitation.stringField("token");

        bodies.add(acceptInvitation(invitationToken, "Chris", JOINER_SECRET).body());
        bodies.add(browser.get("/api/auth/me").body());
        bodies.add(browser.get("/api/households/current").body());
        bodies.add(browser.get("/api/households/current/members").body());
        bodies.add(browser.get("/api/auth/devices").body());
        bodies.add(phone.get("/api/auth/devices").body());
        bodies.add(phone.post("/api/auth/logout", null).body());
        bodies.add(browser.post("/api/auth/logout", null).body());

        return new Journey(
                bodies, bearerToken, invitationToken, decoded(sessionCookie), sessionCookie);
    }

    /** Spring Session's cookie is the base64 of the server-side id; both forms are credentials. */
    private static String decoded(String sessionCookie) {
        if (sessionCookie.isBlank()) {
            throw new AssertionError("the journey did not obtain a session cookie");
        }
        return new String(
                Base64.getDecoder().decode(sessionCookie.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
    }

    private String issueTokenCapturing(List<String> bodies) {
        ApiResponse response =
                anonymous()
                        .post(
                                "/api/auth/token",
                                """
                                {"email":"%s","password":"%s","deviceLabel":"Ada's phone"}
                                """
                                        .formatted(OWNER_EMAIL, OWNER_SECRET));
        bodies.add(response.body());
        return response.stringField("token");
    }

    private void captureLogs(Level level) {
        captured.start();
        root.setLevel(level);
        root.addAppender(captured);
    }

    private static java.util.function.Predicate<ILoggingEvent> everything() {
        return event -> true;
    }

    private List<String> loggedLines(java.util.function.Predicate<ILoggingEvent> matching) {
        return captured.list.stream()
                .filter(matching)
                .map(event -> event.getLoggerName() + " " + event.getFormattedMessage())
                .toList();
    }

    private String storedPasswordHash() {
        return jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE email = CAST(? AS citext)",
                String.class,
                OWNER_EMAIL);
    }

    /**
     * @param sessionId the server-side id
     * @param sessionCookie the same id as the browser carries it — a credential in either form
     */
    private record Journey(
            List<String> bodies,
            String bearerToken,
            String invitationToken,
            String sessionId,
            String sessionCookie) {}
}
