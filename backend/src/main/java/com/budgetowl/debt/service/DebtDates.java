package com.budgetowl.debt.service;

import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.InvalidRequestException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * The dates a payment or a statement may carry (ADR-0043, docs/features/debt-plan.md).
 *
 * <p><b>No later than the latest calendar date anywhere on Earth</b> — today in UTC+14. There is no
 * household time zone in this slice, and the server's clock is UTC, so "today" by the server is
 * yesterday for a household in Sydney on the morning of the 1st. Refusing their payment as
 * future-dated would push them to date it the 31st, into a month whose interest is already booked,
 * and the new month would never be charged. With the bound at UTC+14 the device's own date is
 * always accepted, and the interest month comes from it. The same reasoning, and the same day of
 * slack, as the weekly check-in's week range and reporting's months.
 *
 * <p><b>No earlier than 1900.</b> A date like {@code -5000-01-01} parses, and used to reach
 * PostgreSQL and come back a {@code 500}. Nobody is recording a payment to a lender from before
 * 1900.
 */
public final class DebtDates {

    /** The earliest date a payment or statement may carry. */
    static final LocalDate EARLIEST = LocalDate.of(1900, 1, 1);

    /** The zone whose date is the latest on Earth. */
    private static final ZoneOffset LATEST_ZONE = ZoneOffset.ofHours(14);

    /** The zone whose date is the earliest on Earth. */
    private static final ZoneOffset EARLIEST_ZONE = ZoneOffset.ofHours(-12);

    private DebtDates() {}

    /** Today somewhere on Earth: the latest date any household can be living in at {@code now}. */
    public static LocalDate latestToday(Instant now) {
        return LocalDate.ofInstant(now, LATEST_ZONE);
    }

    /** The earliest date any household can be living in at {@code now}: today in UTC−12. */
    public static LocalDate earliestToday(Instant now) {
        return LocalDate.ofInstant(now, EARLIEST_ZONE);
    }

    /**
     * {@code date}, if a household could have meant it.
     *
     * @throws InvalidRequestException {@code 400 validation-failed} naming {@code field}, for a
     *     missing date, one before 1900, or one later than today anywhere on Earth
     */
    static LocalDate requirePlausible(LocalDate date, String field, Instant now) {
        if (date == null) {
            throw refused("a date is required", field);
        }
        if (date.isBefore(EARLIEST)) {
            throw refused("a date cannot be before 1900", field);
        }
        if (date.isAfter(latestToday(now))) {
            // A payment somebody intends to make is not a payment. The balance would move for a
            // month that has not happened, and the audit trail would carry a future fact.
            throw refused("a date cannot be in the future", field);
        }
        return date;
    }

    private static InvalidRequestException refused(String detail, String field) {
        return new InvalidRequestException(
                ErrorCode.VALIDATION_FAILED, detail, Map.of("field", field));
    }
}
