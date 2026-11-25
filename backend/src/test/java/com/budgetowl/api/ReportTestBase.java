package com.budgetowl.api;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What every report integration test needs: a clock it can stop, and the ledger rows reports sum.
 *
 * <p><b>One clock, one Spring context for every report test.</b> Every subclass imports the same
 * {@link ReportClock}, so they share a context rather than each starting their own.
 *
 * <p><b>The clock moves; it is not fixed before start-up.</b> {@code DemoFixtureIT} found why: an
 * invitation takes its {@code created_at} from the database and its expiry from this clock, so a
 * clock fixed in the past makes {@code seedHousehold} insert an invitation that expired before it
 * was created. So it runs at real time while a test seeds, and {@link #readAt} stops it at the
 * instant the report is read — the only moment "which month is complete" is decided.
 *
 * <p>Report bodies are parsed into a tree here, which {@link ApiResponse} deliberately avoids. The
 * disclosure assertions still read the raw text; these tests are about arithmetic, and the nested
 * months and categories cannot be read reliably with a regular expression.
 */
@Import(ReportTestBase.ReportClock.class)
abstract class ReportTestBase extends ApiTestBase {

    /** Inside 2026-08, mid-month and mid-day so no boundary is load-bearing. */
    protected static final Instant IN_AUGUST_2026 = Instant.parse("2026-08-20T12:00:00Z");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MovableClock clock;

    @BeforeEach
    void runAtRealTime() {
        clock.moveTo(Instant.now());
    }

    /** Stops the clock where the report should be read from. */
    protected void readAt(Instant instant) {
        clock.moveTo(instant);
    }

    /** A clock a test moves. Shared by every report test's context. */
    static final class MovableClock extends Clock {

        private volatile Instant instant = Instant.now();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void moveTo(Instant when) {
            this.instant = when;
        }
    }

    @TestConfiguration
    static class ReportClock {

        /** Overrides {@code CoreConfig.clock()} for the report tests' context only. */
        @Bean
        @Primary
        MovableClock reportClock() {
            return new MovableClock();
        }
    }

    // ---------------------------------------------------------------------------------- reads

    protected static JsonNode json(ApiResponse response) {
        if (response.status() != 200) {
            throw new AssertionError(
                    "expected 200, got " + response.status() + ": " + response.body());
        }
        return JSON.readTree(response.body());
    }

    protected JsonNode incomeAndSpending(ApiClient client, String from, String to) {
        return json(client.get("/api/reports/income-and-spending?from=" + from + "&to=" + to));
    }

    protected JsonNode spendingByCategory(ApiClient client, String from, String to) {
        return json(client.get("/api/reports/spending-by-category?from=" + from + "&to=" + to));
    }

    /** One currency's block of a report, by code. */
    protected static JsonNode currency(JsonNode report, String code) {
        for (JsonNode currency : report.get("currencies")) {
            if (code.equals(currency.get("currency").asString())) {
                return currency;
            }
        }
        throw new AssertionError("no " + code + " in " + report);
    }

    /** One month of report 1's currency block, by period. */
    protected static JsonNode month(JsonNode currency, String period) {
        for (JsonNode month : currency.get("months")) {
            if (period.equals(month.get("period").asString())) {
                return month;
            }
        }
        throw new AssertionError("no " + period + " in " + currency);
    }

    /** One top-level row of report 2's currency block, by name. */
    protected static JsonNode category(JsonNode currency, String name) {
        for (JsonNode row : currency.get("categories")) {
            if (name.equals(row.get("name").asString())) {
                return row;
            }
        }
        throw new AssertionError("no " + name + " in " + currency);
    }

    protected static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            throw new AssertionError(field + " is not a JSON string in " + node);
        }
        return value.asString();
    }

    // ------------------------------------------------------------------------------- fixtures

    /** An account in any currency, created through the API, with a zero opening balance. */
    protected UUID account(ApiClient client, String name, String currency) {
        ApiResponse response =
                client.post(
                        "/api/accounts",
                        """
                        {"name":"%s","type":"CHECKING","currency":"%s","openingBalance":"0.0000"}
                        """
                                .formatted(name, currency));
        if (response.status() != 201) {
            throw new AssertionError("could not create an account: " + response.body());
        }
        return UUID.fromString(response.stringField("id"));
    }

    /** A same-currency transfer, both legs written by the transfer endpoint. */
    protected UUID transfer(
            ApiClient client, UUID fromAccount, UUID toAccount, String date, String amount) {
        return transfer(client, fromAccount, toAccount, date, amount, null);
    }

    /**
     * A transfer; with {@code received} it may cross currencies.
     *
     * @return the transfer group's id
     */
    protected UUID transfer(
            ApiClient client,
            UUID fromAccount,
            UUID toAccount,
            String date,
            String amount,
            String received) {
        ApiResponse response =
                client.post(
                        "/api/transfers",
                        """
                        {"fromAccountId":"%s","toAccountId":"%s","date":"%s","amount":"%s"%s}
                        """
                                .formatted(
                                        fromAccount,
                                        toAccount,
                                        date,
                                        amount,
                                        received == null
                                                ? ""
                                                : ",\"amountReceived\":\"" + received + "\""));
        if (response.status() != 201) {
            throw new AssertionError("could not create a transfer: " + response.body());
        }
        return UUID.fromString(response.stringField("id"));
    }
}
