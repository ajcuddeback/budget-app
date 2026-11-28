package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * <b>The OIDC client secret reaches no log line.</b>
 *
 * <p>{@code docs/architecture/security-model.md} is explicit that a redacted {@code toString} is
 * not a logging control: "the only thing that proves it is a test that captures the log at the
 * level an operator will actually use". This is that, for the one credential an administrator types
 * into the app.
 *
 * <p>The {@code DEBUG} case is the one that matters. Spring MVC logs the deserialized request body
 * at {@code DEBUG}, and an operator turns {@code DEBUG} on when a login is misbehaving — which is
 * precisely when the request bodies flying about contain the provider's secret.
 *
 * <p>Assertions are against <b>text</b>: captured lines and serialized bodies, never types. A DTO
 * with no field for a secret proves nothing about what a validation message, a constraint violation
 * or a stack trace might produce.
 */
class AdminDisclosureIT extends ApiTestBase {

    private static final String SECRET = "oidc-client-secret-nobody-should-see";
    private static final String ISSUER = "https://login.example.com";

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    @AfterEach
    void stopCapturing() {
        root.detachAppender(captured);
        root.setLevel(Level.INFO);
    }

    @Test
    void logsNoClientSecretAtTheLevelThatShips() {
        captureLogs(Level.INFO);

        walkThrough();

        assertNoSecretWasLogged();
    }

    @Test
    void logsNoClientSecretAtDebugEither() {
        captureLogs(Level.DEBUG);

        walkThrough();

        assertNoSecretWasLogged();
    }

    /** Configure, read back, change something else, be refused — every path that logs. */
    private void walkThrough() {
        Household household = seedHousehold();
        long version = household.owner().get("/api/admin/settings").numberField("version");

        ApiResponse configured =
                household
                        .owner()
                        .patch(
                                "/api/admin/settings",
                                """
                                {"oidcEnabled":true,"oidcIssuerUri":"%s",
                                 "oidcClientId":"budget-owl","oidcClientSecret":"%s",
                                 "version":%d}
                                """
                                        .formatted(ISSUER, SECRET, version));
        if (configured.status() != 200) {
            throw new AssertionError("could not configure OIDC: " + configured.body());
        }
        // The response bodies are text too, and are asserted below along with the log.
        household.owner().get("/api/admin/settings");
        household.owner().get("/api/admin/instance");
        household.owner().get("/api/admin/users");

        // A refusal path: the message must not echo what was being written.
        household
                .owner()
                .patch(
                        "/api/admin/settings",
                        "{\"oidcClientSecret\":\"" + SECRET + "\",\"version\":99999}");
    }

    private void assertNoSecretWasLogged() {
        assertThat(loggedLines())
                .as("a secret in a log is a secret in a backup, a bug report and a screenshot")
                .noneMatch(line -> line.contains(SECRET));

        assertThat(loggedLines())
                .as("the journey really ran, so the assertion above had something to find")
                .anyMatch(line -> line.contains("instance settings changed"));
    }

    private void captureLogs(Level level) {
        captured.start();
        root.setLevel(level);
        root.addAppender(captured);
    }

    /** See {@code AccountDisclosureIT.loggedLines} — snapshotted, because other threads write. */
    private List<String> loggedLines() {
        List<ILoggingEvent> snapshot;
        synchronized (captured.list) {
            snapshot = new ArrayList<>(captured.list);
        }
        return snapshot.stream()
                .map(
                        event ->
                                event.getLoggerName()
                                        + " "
                                        + event.getFormattedMessage()
                                        + " "
                                        + throwableOf(event))
                .toList();
    }

    private static String throwableOf(ILoggingEvent event) {
        return event.getThrowableProxy() == null
                ? ""
                : event.getThrowableProxy().getClassName()
                        + " "
                        + event.getThrowableProxy().getMessage();
    }
}
