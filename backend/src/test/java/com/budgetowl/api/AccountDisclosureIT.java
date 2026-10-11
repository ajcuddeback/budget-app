package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * <b>{@code lastFour} never reaches a log.</b> Neither do account names or amounts.
 *
 * <p>ADR-0028 accepted storing a partial financial identifier on exactly these terms: it is never
 * written to a log, never placed in an error message or a problem-detail body, and never put in a
 * URL or query string where a reverse proxy or access log would capture it. The ADR's own
 * consequences section makes an explicit assertion on the create, update <em>and</em> error paths a
 * requirement rather than a style note, and docs/features/accounts.md repeats it.
 *
 * <p>Modelled on {@code CredentialDisclosureIT}, which is what caught a password reaching a log in
 * slice 2. Like that test, the assertions are made against <b>text</b>: the captured log lines and
 * the serialized response bodies, not deserialized types. A DTO with no field for a secret proves
 * nothing about what a {@code toString}, a validation message or a database error might produce.
 *
 * <p>Run at {@code INFO}, which is what ships, and again at {@code DEBUG}, which is what an
 * operator turns on when something is wrong — the worst possible moment for a log to start
 * collecting financial identifiers.
 */
class AccountDisclosureIT extends ApiTestBase {

    /** The digits that must never appear in a log line, and the name and amounts beside them. */
    private static final String DIGITS = "4412";

    private static final String OTHER_DIGITS = "1009";
    private static final String REJECTED_DIGITS = "98765";
    private static final String ACCOUNT_NAME = "Chase Sapphire";
    private static final String AMOUNT = "-9876.5432";

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    @AfterEach
    void stopCapturing() {
        root.detachAppender(captured);
        root.setLevel(Level.INFO);
    }

    @Test
    void logsNoDigitsNoNameAndNoAmountAtTheLevelThatShips() {
        captureLogs(Level.INFO);
        Journey journey = walkThrough();

        List<String> logged = loggedLines(everything());

        assertThat(logged)
                .as("the journey logged nothing at all, so this proves nothing")
                .isNotEmpty();
        assertNothingSensitiveIn(logged);
        assertThat(journey.bodies()).isNotEmpty();
    }

    @Test
    void logsNoDigitsNoNameAndNoAmountEvenWithDebugLoggingOn() {
        captureLogs(Level.DEBUG);
        walkThrough();

        assertNothingSensitiveIn(loggedLines(everything()));
    }

    @Test
    void logsTheAccountEventsThatAreTheAuditTrailByIdAlone() {
        captureLogs(Level.INFO);
        Journey journey = walkThrough();

        String ours =
                String.join(
                        "\n",
                        loggedLines(event -> event.getLoggerName().startsWith("com.budgetowl")));

        assertThat(ours)
                .contains("account created")
                .contains("account updated")
                .contains("account archived")
                .contains("account unarchived")
                .contains("account deleted");
        assertThat(ours)
                .as("an audit line names the row and the actor, and nothing else")
                .contains("accountId=" + journey.accountId());
    }

    @Test
    void stillReturnsTheDigitsToTheClientThatIsEntitledToThem() {
        // The pair of the assertions above: a field that is never returned is trivially never
        // logged, and would be a broken feature rather than a protected one.
        captureLogs(Level.INFO);
        Journey journey = walkThrough();

        assertThat(journey.bodies().stream().filter(body -> body.contains(DIGITS)).toList())
                .as("the digits come back on create, on read and on update")
                .isNotEmpty();
    }

    @Test
    void keepsTheDigitsOutOfTheProblemBodyWhenTheyAreRejected() {
        captureLogs(Level.INFO);
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .owner()
                        .post(
                                "/api/accounts",
                                """
                                {"name":"%s","lastFour":"%s","type":"CHECKING","currency":"GBP",
                                 "openingBalance":"%s"}
                                """
                                        .formatted(ACCOUNT_NAME, REJECTED_DIGITS, AMOUNT));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body())
                .as("the field is named; the value it rejected is not echoed")
                .contains("lastFour")
                .doesNotContain(REJECTED_DIGITS)
                .doesNotContain(AMOUNT);
        assertNothingSensitiveIn(loggedLines(everything()));
    }

    @Test
    void keepsTheDigitsOutOfTheProblemInstanceOnEveryErrorPath() {
        captureLogs(Level.DEBUG);
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), ACCOUNT_NAME, DIGITS);

        List<ApiResponse> refusals =
                List.of(
                        household.viewer().delete("/api/accounts/" + id),
                        household.owner().get("/api/accounts/" + UUID.randomUUID()),
                        household
                                .owner()
                                .patch("/api/accounts/" + id, "{\"version\":99,\"name\":\"X\"}"),
                        household
                                .owner()
                                .patch(
                                        "/api/accounts/" + id,
                                        "{\"version\":0,\"currency\":\"ZZZ\"}"),
                        household.owner().post("/api/accounts", "{not json"));

        for (ApiResponse refusal : refusals) {
            assertThat(refusal.status()).isGreaterThanOrEqualTo(400);
            assertThat(refusal.body())
                    .as("a problem body carries a code and a correlation id, never a value")
                    .doesNotContain(DIGITS)
                    .doesNotContain(ACCOUNT_NAME);
        }
        assertNothingSensitiveIn(loggedLines(everything()));
    }

    @Test
    void keepsTheDigitsOutOfTheLogWhenTheDatabaseRefusesTheRow() {
        // A PostgreSQL CHECK violation puts the entire rejected row in its DETAIL, which here
        // would mean last_four and the name (docs/memory/gotchas.md). Reached by writing straight
        // to the table, because the edge correctly refuses five digits before the database sees
        // them — the point is what happens when something gets past the edge.
        captureLogs(Level.DEBUG);
        Household household = seedHousehold();
        UUID householdId = jdbc.queryForObject("SELECT id FROM households", UUID.class);
        createAccount(household.owner(), ACCOUNT_NAME, DIGITS);

        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () ->
                                        jdbc.update(
                                                """
                                                INSERT INTO accounts (id, household_id, name,
                                                        last_four, type, currency, opening_balance)
                                                VALUES (?, ?, ?, ?, 'CHECKING', 'GBP', 0)
                                                """,
                                                UUID.randomUUID(),
                                                householdId,
                                                ACCOUNT_NAME,
                                                REJECTED_DIGITS)))
                .as("ck_accounts_last_four is what stays true when the edge is bypassed")
                .isNotNull();

        assertNothingSensitiveIn(loggedLines(everything()));
    }

    /**
     * Create with digits, read, rename, change the digits, archive, un-archive, delete — plus the
     * refusals, because an error path logs through a different code path from a success.
     */
    private Journey walkThrough() {
        List<String> bodies = new ArrayList<>();
        Household household = seedHousehold();

        ApiResponse created =
                household
                        .owner()
                        .post(
                                "/api/accounts",
                                """
                                {"name":"%s","lastFour":"%s","type":"CREDIT_CARD","currency":"GBP",
                                 "openingBalance":"%s"}
                                """
                                        .formatted(ACCOUNT_NAME, DIGITS, AMOUNT));
        bodies.add(created.body());
        UUID id = UUID.fromString(created.stringField("id"));

        bodies.add(household.owner().get("/api/accounts").body());
        bodies.add(household.owner().get("/api/accounts/" + id).body());
        bodies.add(
                household
                        .owner()
                        .patch(
                                "/api/accounts/" + id,
                                """
                                {"version":0,"lastFour":"%s","name":"%s","openingBalance":"%s"}
                                """
                                        .formatted(OTHER_DIGITS, ACCOUNT_NAME, AMOUNT))
                        .body());
        bodies.add(
                household
                        .owner()
                        .post(
                                "/api/accounts",
                                """
                                {"name":"%s","lastFour":"%s","type":"CHECKING","currency":"GBP",
                                 "openingBalance":"%s"}
                                """
                                        .formatted(ACCOUNT_NAME, REJECTED_DIGITS, AMOUNT))
                        .body());
        bodies.add(household.viewer().post("/api/accounts/" + id + "/archive", null).body());
        bodies.add(household.owner().post("/api/accounts/" + id + "/archive", null).body());
        bodies.add(household.owner().post("/api/accounts/" + id + "/unarchive", null).body());
        bodies.add(household.owner().delete("/api/accounts/" + id).body());
        bodies.add(household.owner().get("/api/accounts/" + id).body());

        return new Journey(bodies, id);
    }

    /**
     * Everything that must not appear in a log line, whatever produced the line.
     *
     * <p>The four-digit values are matched with word boundaries, and the identifiers a log is
     * <em>allowed</em> to contain are masked first — see {@link #withoutPermittedIdentifiers}.
     * Searching a DEBUG log for a bare four-digit substring is otherwise a coin flip: the full
     * suite caught {@code HikariProxyConnection@2092234412} as a leak of {@code 4412}, which is a
     * flaky test rather than a finding, and docs/guides/testing-style.md is unambiguous that a
     * flaky test is a broken one.
     */
    private static final List<java.util.regex.Pattern> NEVER_LOGGED =
            List.of(
                    java.util.regex.Pattern.compile("\\b" + DIGITS + "\\b"),
                    java.util.regex.Pattern.compile("\\b" + OTHER_DIGITS + "\\b"),
                    java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(REJECTED_DIGITS)),
                    java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(ACCOUNT_NAME)),
                    java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(AMOUNT)),
                    java.util.regex.Pattern.compile(java.util.regex.Pattern.quote("9876.5432")));

    /** Emits nothing but timings. No user data can reach it, and its numbers are pure noise. */
    private static final String METRICS_LOGGER = "org.hibernate.session.metrics";

    /**
     * Asserts on the offending lines rather than on the whole log, so a failure names the logger
     * that leaked instead of printing a megabyte of DEBUG output around it.
     */
    private static void assertNothingSensitiveIn(List<String> lines) {
        assertThat(
                        lines.stream()
                                .filter(line -> !line.startsWith(METRICS_LOGGER))
                                .map(AccountDisclosureIT::withoutPermittedIdentifiers)
                                .filter(
                                        line ->
                                                NEVER_LOGGED.stream()
                                                        .anyMatch(
                                                                pattern ->
                                                                        pattern.matcher(line)
                                                                                .find()))
                                .toList())
                .as("no log line may carry a partial financial identifier, a name or an amount")
                .isEmpty();
    }

    /**
     * Blanks what a log line is entitled to contain, before searching it for what it is not.
     *
     * <p>Audit lines name rows and actors by id, which is the whole design — "log identifiers, not
     * values" (docs/architecture/security-model.md) — and the framework prints object hashes,
     * session ids and durations. All of those are long digit or hex runs, and a four-digit
     * identifier is a substring of plenty of them by chance. Masking them keeps the search for a
     * real leak precise instead of probabilistic; a leak of {@code lastFour} looks like {@code
     * lastFour=4412} or {@code "lastFour":"4412"}, which survives every rule below.
     *
     * <p>Runs of six digits or more, not five: the rejected five-digit value is one of the things
     * being searched for, and masking it would make that assertion unfalsifiable. Five-digit
     * numbers a log does produce cannot collide with it — a port is at most 65535.
     */
    private static String withoutPermittedIdentifiers(String line) {
        return line.replaceAll(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                                + "-[0-9a-fA-F]{12}",
                        "{uuid}")
                .replaceAll("[0-9]{6,}", "{number}")
                .replaceAll("[0-9]{1,4} ?(ns|ms|s)\\b", "{duration}");
    }

    private void captureLogs(Level level) {
        captured.start();
        root.setLevel(level);
        root.addAppender(captured);
    }

    private static Predicate<ILoggingEvent> everything() {
        return event -> true;
    }

    private List<String> loggedLines(Predicate<ILoggingEvent> matching) {
        return captured.list.stream()
                .filter(matching)
                .map(
                        event ->
                                event.getLoggerName()
                                        + " "
                                        + event.getFormattedMessage()
                                        + " "
                                        + throwableOf(event))
                .toList();
    }

    /**
     * The throwable's own text, flattened into the line. A stack trace is part of what a log file
     * contains, and a database error's {@code DETAIL} travels in an exception message rather than
     * in the formatted message — which is precisely where the rejected row would appear.
     */
    private static String throwableOf(ILoggingEvent event) {
        return event.getThrowableProxy() == null
                ? ""
                : event.getThrowableProxy().getClassName()
                        + ": "
                        + event.getThrowableProxy().getMessage();
    }

    private record Journey(List<String> bodies, UUID accountId) {}
}
