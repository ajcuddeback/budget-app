package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * <b>No transfer figure, and no account name, ever reaches a log.</b>
 *
 * <p>{@code docs/features/transfers.md} promises it and {@code docs/architecture/security-model.md}
 * is explicit about what counts as proof: "a redacted {@code toString} is not a logging control …
 * the only thing that proves it is a test that captures the log at the level an operator will
 * actually use". Slice 8 added four redacted {@code toString}s and a service that logs on every
 * write, and shipped no such test until a security review pointed at the gap.
 *
 * <p>A transfer is the worst case for this. It carries <b>two</b> amounts, two account names and a
 * note, it touches two accounts in one request, and across currencies it also carries a derived
 * rate that would identify the pair even if the amounts did not.
 *
 * <p>Assertions are against <b>text</b> — captured lines and serialized bodies — not against types.
 * A DTO with no field for a figure proves nothing about what a {@code toString}, a validation
 * message or a database error might produce. Modelled on {@code AccountDisclosureIT}, which is
 * modelled on the test that caught a password in a log in slice 2.
 */
class TransferDisclosureIT extends ApiTestBase {

    private static final String SENT = "200.0000";
    private static final String RECEIVED = "228.4000";
    private static final String RATE = "1.142000";
    private static final String FROM_NAME = "Chase Sapphire";
    private static final String TO_NAME = "Barclays Holiday";
    private static final String NOTE = "money for the ferry";

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    @AfterEach
    void stopCapturing() {
        root.detachAppender(captured);
        root.setLevel(Level.INFO);
    }

    @Test
    void logsNoFigureNoRateAndNoAccountNameAtTheLevelThatShips() {
        captureLogs(Level.INFO);

        walkThrough();

        assertNothingSensitiveWasLogged();
    }

    @Test
    void logsNoFigureNoRateAndNoAccountNameAtDebugEither() {
        // What an operator turns on when something is wrong — the worst possible moment for a
        // log to start collecting what somebody moved between their accounts. Spring MVC logs
        // the deserialized request body at DEBUG, which is what every redacted toString is for.
        captureLogs(Level.DEBUG);

        walkThrough();

        assertNothingSensitiveWasLogged();
    }

    /** Create, read, edit, be refused, and delete — every path that logs or renders. */
    private void walkThrough() {
        Household household = seedHousehold();
        UUID pounds = createAccountIn(household.owner(), FROM_NAME, "GBP");
        UUID euros = createAccountIn(household.owner(), TO_NAME, "EUR");

        ApiResponse created =
                household
                        .owner()
                        .post(
                                "/api/transfers",
                                """
                                {"fromAccountId":"%s","toAccountId":"%s","date":"2026-08-12",
                                 "amount":"200.00","amountReceived":"228.40","note":"%s"}
                                """
                                        .formatted(pounds, euros, NOTE));
        if (created.status() != 201) {
            throw new AssertionError("could not create a transfer: " + created.body());
        }
        UUID id = UUID.fromString(created.stringField("id"));

        household.owner().get("/api/transfers/" + id);
        household
                .owner()
                .patch(
                        "/api/transfers/" + id,
                        "{\"note\":\"ferry\",\"version\":" + created.numberField("version") + "}");

        // The refusal paths too: a rejected amount must not be echoed into a log or a body, and
        // a stale-version conflict must not print what was being written.
        household
                .owner()
                .patch("/api/transfers/" + id, "{\"amount\":\"999999.1234\",\"version\":0}");
        household
                .owner()
                .post(
                        "/api/transfers",
                        """
                        {"fromAccountId":"%s","toAccountId":"%s","date":"2026-08-12",
                         "amount":"77777.7777"}
                        """
                                .formatted(pounds, euros));

        household.owner().delete("/api/transfers/" + id);
    }

    private void assertNothingSensitiveWasLogged() {
        List<String> logged = loggedLines();

        assertThat(logged)
                .as("what somebody moved between their own accounts is financial data")
                .noneMatch(line -> line.contains(SENT))
                .noneMatch(line -> line.contains(RECEIVED))
                .noneMatch(line -> line.contains("200.00"))
                .noneMatch(line -> line.contains("228.40"))
                // The derived rate identifies the pair even where the amounts do not appear.
                .noneMatch(line -> line.contains(RATE))
                .noneMatch(line -> line.contains(FROM_NAME))
                .noneMatch(line -> line.contains(TO_NAME))
                .noneMatch(line -> line.contains(NOTE))
                // The rejected figures, which a validation message is most tempted to echo back.
                .noneMatch(line -> line.contains("999999.1234"))
                .noneMatch(line -> line.contains("77777.7777"));

        assertThat(logged)
                .as("the journey really did run, so the assertions above had something to find")
                .anyMatch(line -> line.contains("transfer created"));
    }

    private UUID createAccountIn(ApiClient client, String name, String currency) {
        ApiResponse response =
                client.post(
                        "/api/accounts",
                        """
                        {"name":"%s","type":"CHECKING","currency":"%s","openingBalance":"0.00"}
                        """
                                .formatted(name, currency));
        if (response.status() != 201) {
            throw new AssertionError("could not create an account: " + response.body());
        }
        return UUID.fromString(response.stringField("id"));
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
