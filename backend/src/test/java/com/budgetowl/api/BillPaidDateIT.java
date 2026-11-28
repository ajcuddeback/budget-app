package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * <b>The day a bill is marked paid is the household's day</b> (docs/features/bills-and-income.md,
 * The paid date). There is no household time zone and the server's clock is UTC, so the one-tap
 * Mark paid sends the device's own date, and the server accepts any date from 1900 to today
 * anywhere on Earth — the bound a debt payment's date has had since ADR-0043.
 *
 * <p>Every test stands at <b>23:00 UTC on August 31st</b>: 09:00 on September 1st in Sydney, and
 * still August 31st everywhere west of UTC+1. It extends {@link ReportTestBase} for its movable
 * clock, so it shares the report tests' Spring context rather than starting one of its own.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class BillPaidDateIT extends ReportTestBase {

    /** 09:00 on September 1st in Sydney (AEST, UTC+10). */
    private static final Instant SYDNEY_MORNING_OF_THE_FIRST =
            Instant.parse("2026-08-31T23:00:00Z");

    private ApiClient owner;
    private UUID rent;

    @BeforeEach
    void rentDueOnTheFirst() {
        owner = seedHousehold().owner();
        rent = createRent(owner);
        readAt(SYDNEY_MORNING_OF_THE_FIRST);
    }

    @Test
    void a_bill_marked_paid_on_the_first_east_of_utc_is_paid_in_the_new_month() {
        ApiResponse paid = markPaid("{\"status\":\"PAID\",\"paidDate\":\"2026-09-01\"}");

        assertThat(paid.status()).as(paid.body()).isEqualTo(200);
        assertThat(paid.stringField("paidDate"))
                .as("the device's own day, not the server's August 31st")
                .isEqualTo("2026-09-01");
        assertThat(storedPaidDate()).isEqualTo("2026-09-01");
    }

    @Test
    void a_client_that_sends_no_date_is_still_dated_by_the_server() {
        ApiResponse paid = markPaid("{\"status\":\"PAID\"}");

        // A phone that has not updated sends no date. It is not refused: it gets the server's UTC
        // day, which is exactly the month-before answer the device's date exists to avoid.
        assertThat(paid.status()).as(paid.body()).isEqualTo(200);
        assertThat(storedPaidDate()).isEqualTo("2026-08-31");
    }

    @Test
    void every_date_from_1900_to_today_anywhere_on_earth_is_accepted() {
        // Today in UTC+14, today in UTC−12, a back-dated payment from the full form, and the floor.
        for (String date : List.of("2026-09-01", "2026-08-31", "2026-08-03", "1900-01-01")) {
            ApiResponse paid = markPaid("{\"status\":\"PAID\",\"paidDate\":\"" + date + "\"}");
            assertThat(paid.status()).as(date + ": " + paid.body()).isEqualTo(200);
            assertThat(storedPaidDate()).isEqualTo(date);
        }
    }

    @Test
    void a_date_nobody_could_be_living_in_is_refused_and_records_nothing() {
        // Tomorrow even in UTC+14; the day before the floor; and two years that parse. Before the
        // bound, the first, second and fourth were stored as paid, and -5000 reached PostgreSQL and
        // came back a 409 "conflict" that named nothing the household could fix.
        for (String date : List.of("2026-09-02", "1899-12-31", "-5000-01-01", "+10000-01-01")) {
            ApiResponse refused = markPaid("{\"status\":\"PAID\",\"paidDate\":\"" + date + "\"}");
            assertThat(refused.status()).as(date + ": " + refused.body()).isEqualTo(400);
            assertThat(refused.body()).contains("\"field\":\"paidDate\"");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM bill_occurrences WHERE bill_id = ?",
                                Long.class,
                                rent))
                .as("a refused statement writes no row")
                .isZero();
    }

    // ------------------------------------------------------------------------------- internals

    private ApiResponse markPaid(String body) {
        return owner.put("/api/bills/" + rent + "/occurrences/2026-09-01", body);
    }

    private String storedPaidDate() {
        return jdbc.queryForObject(
                "SELECT paid_date::text FROM bill_occurrences"
                        + " WHERE bill_id = ? AND due_date = '2026-09-01' AND status = 'PAID'",
                String.class,
                rent);
    }

    private static UUID createRent(ApiClient client) {
        ApiResponse created =
                client.post(
                        "/api/bills",
                        """
                        {"name":"Rent","expectedAmount":"1200.00",
                         "schedule":{"kind":"MONTHLY","dayOfMonth":1}}
                        """);
        if (created.status() != 201) {
            throw new AssertionError("could not create a bill: " + created.body());
        }
        return UUID.fromString(created.stringField("id"));
    }
}
