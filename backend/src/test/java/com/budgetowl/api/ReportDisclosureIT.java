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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * <b>No amount and no category name reaches a log</b>, from either report, in either
 * representation, at the level that ships or at {@code DEBUG} (docs/features/reporting.md, attack
 * 6).
 *
 * <p>Modelled on {@code AccountDisclosureIT}: the assertions are made against <b>text</b> — the
 * captured log lines — because a redacted {@code toString} is not a logging control on its own. It
 * only covers the places something calls {@code toString}. Spring MVC's own {@code DEBUG} line for
 * a returned body, a {@code String} body logged as its content, Hibernate printing a bound
 * parameter: each would put a household's figures in a log file, and only capturing the log at the
 * level an operator will actually use proves none of them does.
 *
 * <p>The figures are distinctive on purpose, and so are the <b>computed</b> ones: a test that only
 * looked for the stored amounts would miss a log line printing a sum.
 *
 * <p>This also closes, for this path, the "no disclosure test for amounts" gap that {@code
 * transactions.md} records for the ledger.
 */
class ReportDisclosureIT extends ReportTestBase {

    /** A category name nobody would type by accident. */
    private static final String NAME = "Zyxwvu therapy";

    private static final String PARENT_NAME = "Qwpoeiru care";

    private static final List<String> FIGURES =
            List.of(
                    // stored
                    "7345.6123",
                    "7345.61",
                    "-418.2719",
                    "418.2719",
                    "-93.0457",
                    "93.0457",
                    // computed: July's spending and kept, and kept averaged over July and August
                    "511.3176",
                    "6741.2490",
                    "6741.249",
                    "3370.6245");

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    /**
     * Pinned to {@code INFO} in {@code application.yml}, so a root logger at {@code DEBUG} alone
     * never reaches this application's own lines. An operator chasing a bug turns both on.
     */
    private final Logger ours = (Logger) LoggerFactory.getLogger("com.budgetowl");

    /**
     * Spring MVC's own loggers. At {@code DEBUG} they print the returned body truncated to 100
     * characters, which for a report is only its periods and currency. At {@code TRACE} they print
     * the whole body — so that is the level at which the redacted {@code toString} on every
     * response record is the only thing between a household's figures and the log.
     */
    private final Logger springWeb = (Logger) LoggerFactory.getLogger("org.springframework.web");

    private Household household;

    @BeforeEach
    void seedDistinctiveFigures() {
        household = seedHousehold();
        UUID current = account(household.owner(), "Current", "GBP");
        UUID parent = createCategory(household.owner(), PARENT_NAME, "EXPENSE", null);
        UUID child = createCategory(household.owner(), NAME, "EXPENSE", parent);
        createTransaction(
                household.owner(), current, "2026-07-02", "7345.6123", seededCategoryId("Salary"));
        createTransaction(household.owner(), current, "2026-07-09", "-418.2719", child);
        createTransaction(household.owner(), current, "2026-07-10", "-93.0457", parent);
        createTransaction(household.owner(), current, "2026-07-11", "-93.0457", null);
    }

    @AfterEach
    void stopCapturing() {
        root.detachAppender(captured);
        root.setLevel(Level.INFO);
        ours.setLevel(Level.INFO);
        springWeb.setLevel(null);
    }

    @Test
    void logsNoFigureAndNoCategoryNameAtTheLevelThatShips() {
        captureLogs(Level.INFO);

        List<String> bodies = readEverything();

        assertNothingSensitiveIn(loggedLines(everything()));
        assertThat(String.join("\n", bodies))
                .as(
                        "the figures really were in the responses, so their absence above means something")
                .contains("6741.2490")
                .contains(NAME);
    }

    @Test
    void logsNoFigureAndNoCategoryNameEvenWithDebugLoggingOn() {
        captureLogs(Level.DEBUG);

        readEverything();

        List<String> logged = loggedLines(everything());
        assertThat(logged)
                .as("DEBUG produced lines, including the report's own, so this proves something")
                .anyMatch(line -> line.contains("report read report=income-and-spending"));
        assertNothingSensitiveIn(logged);
    }

    @Test
    void logsNoFigureAndNoCategoryNameWithTheWebLayerTraced() {
        captureLogs(Level.DEBUG);
        springWeb.setLevel(Level.TRACE);

        readEverything();

        List<String> logged = loggedLines(everything());
        assertThat(logged)
                .as("TRACE printed the bodies Spring MVC wrote, so this proves something")
                .anyMatch(line -> line.contains("Writing [IncomeAndSpendingResponse["));
        assertNothingSensitiveIn(logged);
    }

    @Test
    void anExportIsLoggedByUserReportAndPeriodsAlone() {
        captureLogs(Level.INFO);

        readEverything();

        List<String> exports =
                loggedLines(
                        event ->
                                event.getLoggerName().startsWith("com.budgetowl")
                                        && event.getFormattedMessage().contains("report exported"));
        assertThat(exports).as("two reports, exported by the owner and by the viewer").hasSize(4);
        assertThat(exports.get(0))
                .contains("report=income-and-spending")
                .contains("userId=" + userIdOf(OWNER_EMAIL))
                .contains("from=2026-07")
                .contains("to=2026-08");
        assertThat(exports.get(3)).contains("userId=" + userIdOf(VIEWER_EMAIL));
        assertNothingSensitiveIn(exports);
    }

    @Test
    void keepsFiguresOutOfTheLogWhenTheRequestIsRefused() {
        captureLogs(Level.DEBUG);

        household.owner().get("/api/reports/income-and-spending?from=2026-09&to=2026-07");
        household.owner().get("/api/reports/spending-by-category?from=2026-07&to=+10000-01");
        household.owner().getAccepting("/api/reports/income-and-spending?from=1&to=2", "text/csv");

        assertNothingSensitiveIn(loggedLines(everything()));
    }

    /** Both reports, both representations, as the owner and as the viewer. */
    private List<String> readEverything() {
        List<String> bodies = new ArrayList<>();
        for (ApiClient client : List.of(household.owner(), household.viewer())) {
            for (String report : List.of("income-and-spending", "spending-by-category")) {
                String path = "/api/reports/" + report + "?from=2026-07&to=2026-08";
                ApiResponse json = client.get(path);
                ApiResponse csv = client.getAccepting(path, "text/csv");
                assertThat(json.status()).isEqualTo(200);
                assertThat(csv.status()).isEqualTo(200);
                bodies.add(json.body());
                bodies.add(csv.body());
            }
        }
        return bodies;
    }

    private void captureLogs(Level level) {
        captured.list.clear();
        captured.start();
        root.addAppender(captured);
        root.setLevel(level);
        ours.setLevel(level);
    }

    private static Predicate<ILoggingEvent> everything() {
        return event -> true;
    }

    private List<String> loggedLines(Predicate<ILoggingEvent> which) {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : List.copyOf(captured.list)) {
            if (!which.test(event)) {
                continue;
            }
            StringBuilder line = new StringBuilder(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                line.append(' ').append(event.getThrowableProxy().getMessage());
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private static void assertNothingSensitiveIn(List<String> lines) {
        String all = String.join("\n", lines);
        assertThat(all).doesNotContain(NAME).doesNotContain(PARENT_NAME);
        for (String figure : FIGURES) {
            assertThat(all).as("figure " + figure).doesNotContain(figure);
        }
    }
}
