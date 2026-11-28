package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * <b>No goal name, amount or note ever reaches a log</b> (docs/features/goals.md, threat model
 * attack 7).
 *
 * <p>A goal's name is the sharpest asset this feature has: <em>IVF</em>, <em>Divorce lawyer</em>,
 * <em>Bail</em> disclose a household's plans, health or relationships with no amount attached. A
 * redacted {@code toString} is not a logging control by itself (docs/memory/gotchas.md), so this
 * captures the log at the level that ships and at {@code DEBUG} — what an operator turns on when
 * something is wrong — across the whole journey, including the refusal paths, which log through
 * different code from a success.
 */
class GoalDisclosureIT extends ApiTestBase {

    private static final String NAME = "IVF clinic deposit";
    private static final String RENAMED = "Second IVF round";
    private static final String NOTE = "from Mum's care home fund";
    private static final String TARGET = "4321.98";
    private static final String START = "1234.56";
    private static final String CONTRIBUTION = "765.43";
    private static final String WITHDRAWAL = "-246.81";
    private static final String PLAN = "137.29";
    private static final String REJECTED = "98765.4321";

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    @AfterEach
    void stopCapturing() {
        root.detachAppender(captured);
        root.setLevel(Level.INFO);
    }

    @Test
    void logsNoGoalNameAmountOrNoteAtTheLevelThatShips() {
        captureLogs(Level.INFO);

        walkThrough();

        assertNothingSensitiveWasLogged();
    }

    @Test
    void logsNoGoalNameAmountOrNoteAtDebugEither() {
        captureLogs(Level.DEBUG);

        walkThrough();

        assertNothingSensitiveWasLogged();
    }

    @Test
    void keepsTheNameOutOfTheLogWhenTheDatabaseRefusesTheRow() {
        // PostgreSQL puts the whole failing row in a CHECK violation's DETAIL. Reached by writing
        // straight to the table, because the edge refuses a zero target before the database sees
        // it — the point is what happens when something gets past the edge.
        captureLogs(Level.DEBUG);
        seedHousehold();
        UUID householdId = jdbc.queryForObject("SELECT id FROM households", UUID.class);

        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () ->
                                        jdbc.update(
                                                """
                                                INSERT INTO goals (id, household_id, name, currency,
                                                                   target_amount)
                                                VALUES (?, ?, ?, 'GBP', 0)
                                                """,
                                                UUID.randomUUID(),
                                                householdId,
                                                NAME)))
                .as("ck_goals_target_amount_positive is what holds when the edge is bypassed")
                .isNotNull();

        assertThat(loggedLines()).noneMatch(line -> line.contains(NAME));
    }

    /**
     * Create, list, read, update, set the plan, preview, contribute, withdraw, correct, and the
     * refusals: a {@code VIEWER} write, a {@code 404}, a stale version, an unparseable body, and a
     * rejected amount. Every path that logs or renders.
     */
    private void walkThrough() {
        Household household = seedHousehold();
        ApiResponse created =
                household
                        .owner()
                        .post(
                                "/api/goals",
                                """
                                {"name":"%s","targetAmount":"%s","startingAmount":"%s",
                                 "targetMonth":"%s","monthlyContribution":"%s"}
                                """
                                        .formatted(
                                                NAME,
                                                TARGET,
                                                START,
                                                YearMonth.from(TODAY).plusMonths(12),
                                                PLAN));
        if (created.status() != 201) {
            throw new AssertionError("could not create a goal: " + created.body());
        }
        UUID goal = UUID.fromString(created.stringField("id"));
        String path = "/api/goals/" + goal;

        household.owner().get("/api/goals");
        household.owner().get(path);
        household
                .owner()
                .patch(
                        path,
                        "{\"name\":\"%s\",\"version\":%d}"
                                .formatted(RENAMED, created.numberField("version")));
        long version = household.owner().get(path).numberField("version");
        household
                .owner()
                .put(
                        path + "/contribution-plan/" + YearMonth.from(TODAY).plusMonths(1),
                        "{\"monthlyAmount\":\"%s\",\"version\":%d}".formatted(PLAN, version));
        household
                .owner()
                .post(path + "/projection-preview", "{\"monthlyAmount\":\"" + PLAN + "\"}");
        household
                .owner()
                .post(
                        "/api/goals/projection-preview",
                        "{\"targetAmount\":\"%s\",\"monthlyAmount\":\"%s\"}"
                                .formatted(TARGET, PLAN));
        ApiResponse contribution =
                household
                        .owner()
                        .post(
                                path + "/contributions",
                                "{\"amount\":\"%s\",\"date\":\"%s\",\"note\":\"%s\"}"
                                        .formatted(CONTRIBUTION, TODAY, NOTE));
        household
                .owner()
                .post(
                        path + "/contributions",
                        "{\"amount\":\"%s\",\"date\":\"%s\"}".formatted(WITHDRAWAL, TODAY));
        household
                .owner()
                .patch(
                        path + "/contributions/" + contribution.stringField("id"),
                        "{\"note\":\"%s again\",\"version\":%d}"
                                .formatted(NOTE, contribution.numberField("version")));
        household.owner().get(path + "/contributions");
        household.owner().get(path + "/contribution-plan");
        household.owner().get("/api/periods/" + YearMonth.from(TODAY) + "/summary");

        // The refusals.
        household
                .viewer()
                .post(
                        path + "/contributions",
                        "{\"amount\":\"%s\",\"date\":\"%s\",\"note\":\"%s\"}"
                                .formatted(CONTRIBUTION, TODAY, NOTE));
        household.owner().get("/api/goals/" + UUID.randomUUID());
        household.owner().patch(path, "{\"name\":\"%s\",\"version\":0}".formatted(NAME));
        household.owner().post("/api/goals", "{\"name\":\"" + NAME + "\",\"targetAmount\":");
        household
                .owner()
                .post(
                        "/api/goals",
                        "{\"name\":\"%s\",\"targetAmount\":\"%s\"}".formatted(NAME, REJECTED));
        household
                .owner()
                .post(
                        "/api/goals",
                        "{\"name\":\"%s\",\"targetAmount\":%s}".formatted(NAME, REJECTED));

        household.owner().post(path + "/archive", null);
        household.owner().delete(path);
    }

    private void assertNothingSensitiveWasLogged() {
        List<String> logged = loggedLines();

        assertThat(logged)
                .as("a goal's name discloses a household's plans with no amount attached")
                .noneMatch(line -> line.contains(NAME))
                .noneMatch(line -> line.contains(RENAMED))
                .noneMatch(line -> line.contains("IVF"))
                .noneMatch(line -> line.contains(NOTE))
                .noneMatch(line -> line.contains("care home"))
                .noneMatch(line -> line.contains(TARGET))
                .noneMatch(line -> line.contains(START))
                .noneMatch(line -> line.contains(CONTRIBUTION))
                .noneMatch(line -> line.contains("246.81"))
                .noneMatch(line -> line.contains(PLAN))
                .noneMatch(line -> line.contains("98765"));

        assertThat(logged)
                .as("the journey really did run, so the assertions above had something to find")
                .anyMatch(line -> line.contains("goal created"))
                .anyMatch(line -> line.contains("goal contribution recorded"));
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
