package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * <b>Nothing from an imported file ever reaches a log</b> — not at the level that ships, and not at
 * {@code DEBUG}, which is what an operator turns on when an import goes wrong
 * (docs/features/file-import.md, "What reaches the logs and error responses").
 *
 * <p>The corpus file carries a sentinel in every field the threat model names: the payee, the memo,
 * the full account number ({@code ACCTID}), the routing number ({@code BANKID}), the online-banking
 * username ({@code INTU.USERID}), the institution, and an amount. The journey below runs every path
 * that logs or renders — preview, commit, a retried commit, a stale commit, every malformed reason,
 * an unreadable row, a {@code VIEWER}'s refusal, a {@code 404}, a request carrying a file name, and
 * a pairing and a split — and the assertion is against the captured text, not against types.
 */
class ImportDisclosureIT extends ImportApiTestBase {

    private static final List<String> SENTINELS =
            List.of(
                    "SENTINEL PAYEE QX7",
                    "SENTINEL MEMO QX7",
                    "8877665544332211",
                    "887766554433",
                    "SENTINELBANK77",
                    "sentinel.userid.qx7",
                    "SENTINEL INSTITUTION QX7",
                    "3141.59",
                    "3141.5900",
                    "BLUE BOTTLE",
                    "ACME PAYROLL",
                    "sentinel-statement-qx7",
                    "31/02/2026",
                    "proc/self/environ");

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    @AfterEach
    void stopCapturing() {
        root.detachAppender(captured);
        root.setLevel(Level.INFO);
    }

    @Test
    void logsNothingFromTheFileAtTheLevelThatShips() {
        captureLogs(Level.INFO);

        walkThrough();

        assertNothingFromTheFileWasLogged();
    }

    @Test
    void logsNothingFromTheFileAtDebugEither() {
        captureLogs(Level.DEBUG);

        walkThrough();

        assertNothingFromTheFileWasLogged();
    }

    private void walkThrough() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", "2211");
        UUID savings = createAccount(household.owner(), "Savings", null);
        byte[] file = fixture("ofx1-happy.qfx");
        String base64 = base64(file);

        ApiResponse previewed = preview(household.owner(), account, "OFX", file, null);
        String digest = previewed.stringField("previewDigest");
        commit(household.owner(), account, "OFX", file, null, digest, null);
        commit(household.owner(), account, "OFX", file, null, digest, null);
        commit(household.owner(), account, "OFX", file, null, "f".repeat(64), null);

        for (String hostile :
                List.of(
                        "ofx1-doctype.ofx",
                        "ofx1-entity.ofx",
                        "ofx1-cdata.ofx",
                        "ofx2-billion-laughs.ofx",
                        "ofx1-unknown-entity.ofx")) {
            preview(household.owner(), account, "OFX", fixture(hostile), null);
        }
        preview(
                household.owner(),
                account,
                "OFX",
                utf8(
                        new String(file, java.nio.charset.StandardCharsets.UTF_8)
                                .replace("SECURITY:NONE", "SECURITY:TYPE1")),
                null);
        preview(household.owner(), account, "CSV", fixture("csv-happy.csv"), CSV_OPTIONS);
        preview(
                household.owner(),
                account,
                "CSV",
                utf8("Date,Payee,Amount\n2026-08-01,\"SENTINEL PAYEE QX7 never closed,-3141.59\n"),
                null);

        preview(household.viewer(), account, "OFX", file, null);
        preview(household.owner(), UUID.randomUUID(), "OFX", file, null);
        household
                .owner()
                .post(
                        "/api/imports/preview",
                        "{\"accountId\":\""
                                + account
                                + "\",\"format\":\"OFX\",\"file\":\""
                                + base64
                                + "\",\"fileName\":\"sentinel-statement-qx7.qfx\"}");

        UUID salary =
                jdbc.queryForObject("SELECT id FROM transactions WHERE amount = 2400", UUID.class);
        UUID moved = createTransaction(household.owner(), savings, "2026-08-06", "-2400.00", null);
        ApiResponse paired =
                household
                        .owner()
                        .post(
                                "/api/transactions/" + salary + "/pair",
                                "{\"withTransactionId\":\""
                                        + moved
                                        + "\",\"version\":0,\"withVersion\":0}");
        household
                .owner()
                .post(
                        "/api/transfers/" + paired.stringField("id") + "/split",
                        "{\"version\":" + paired.numberField("version") + "}");

        UUID batch = jdbc.queryForObject("SELECT id FROM import_batches", UUID.class);
        household.owner().delete("/api/imports/" + batch);

        assertThat(captured.list)
                .as("the walk ran, so the assertions have something to find")
                .anyMatch(event -> event.getFormattedMessage().contains("import commit"));
        assertThat(captured.list)
                .noneMatch(
                        event -> event.getFormattedMessage().contains(base64.substring(100, 160)));
    }

    private void assertNothingFromTheFileWasLogged() {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : captured.list) {
            lines.add(event.getFormattedMessage());
            for (IThrowableProxy cause = event.getThrowableProxy();
                    cause != null;
                    cause = cause.getCause()) {
                lines.add(cause.getClassName() + ": " + cause.getMessage());
            }
        }
        for (String sentinel : SENTINELS) {
            assertThat(lines)
                    .as("a log line carrying %s", sentinel)
                    .noneMatch(line -> line.contains(sentinel));
        }
        assertThat(lines)
                .as("the one line per request carries counts and ids, never content")
                .anyMatch(
                        line ->
                                line.contains("import preview")
                                        && line.contains("new=5")
                                        && line.contains("outcome=ok"));
    }

    private void captureLogs(Level level) {
        root.setLevel(level);
        captured.start();
        root.addAppender(captured);
    }
}
