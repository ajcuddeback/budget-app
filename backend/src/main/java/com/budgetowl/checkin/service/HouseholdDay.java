package com.budgetowl.checkin.service;

import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.InvalidRequestException;
import com.budgetowl.debt.service.DebtDates;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * The day a completion records things on, in the household's own calendar (ADR-0043,
 * docs/features/weekly-check-in.md, Departures; docs/features/debt-plan.md, The split, rule 8).
 *
 * <p>There is no household time zone and the server's clock is UTC, so on the morning of the 1st in
 * Sydney the server is still on the 31st. A bill marked paid then, dated by the server, lands in
 * the month before: for a debt's bill, a month already booked, so its payment is all principal and
 * the new month is never charged. So the client sends its own date with the completion, as Bills
 * &amp; Income's own form already does, and that is the day a bill is marked paid on.
 *
 * @param markedPaidOn the day an overdue bill marked paid is dated, and its debt payment: the
 *     client's date when it sent one, else the server's (a client from before the field)
 * @param latest the latest date a payment may carry: the client's date, or, without one, the latest
 *     date anywhere on Earth, so a true date is never moved into the month before
 */
record HouseholdDay(LocalDate markedPaidOn, LocalDate latest) {

    /**
     * @param sent the client's own date, or null
     * @param serverToday the server's date, in UTC
     * @throws InvalidRequestException {@code 400 validation-failed} naming {@code today} for a date
     *     that is not today anywhere on Earth at {@code now}
     */
    static HouseholdDay of(LocalDate sent, Instant now, LocalDate serverToday) {
        if (sent == null) {
            return new HouseholdDay(serverToday, DebtDates.latestToday(now));
        }
        if (sent.isBefore(DebtDates.earliestToday(now))
                || sent.isAfter(DebtDates.latestToday(now))) {
            // Not a calendar anybody is living in now: a back- or forward-dated completion.
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "today must be the date somewhere on Earth now",
                    Map.of("field", "today"));
        }
        return new HouseholdDay(sent, sent);
    }

    /** {@code date}, or {@link #latest} when it is later: a debt payment is never future-dated. */
    LocalDate noLaterThanToday(LocalDate date) {
        return date.isAfter(latest) ? latest : date;
    }
}
