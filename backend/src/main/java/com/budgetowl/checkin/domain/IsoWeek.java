package com.budgetowl.checkin.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An ISO-8601 week, Monday to Sunday, written {@code 2026-W34}.
 *
 * <p><b>Parsed strictly</b> (threat model, attack 10): exactly four digits, {@code -W}, two digits,
 * and a week that exists in that year — a {@code W53} only in a year that has one. Anything else is
 * refused before any date arithmetic runs, so a {@code +10000-W01} or a 5 KB string can never reach
 * {@code LocalDate} and become a {@code 500}.
 *
 * @param year the week-based year, which differs from the calendar year at the edges of January
 * @param week 1 to 52, or 53 in a long year
 */
public record IsoWeek(int year, int week) implements Comparable<IsoWeek> {

    private static final Pattern SHAPE = Pattern.compile("^\\d{4}-W\\d{2}$");

    /** The years a week may name. Not a product rule: outside them a request means nothing. */
    private static final int EARLIEST_YEAR = 1900;

    private static final int LATEST_YEAR = 9998;

    public IsoWeek {
        if (year < EARLIEST_YEAR || year > LATEST_YEAR) {
            throw new IllegalArgumentException("a week's year is out of range");
        }
        if (week < 1 || week > weeksIn(year)) {
            throw new IllegalArgumentException("no such week in that year");
        }
    }

    /** The week as the API writes it, or empty for anything that is not one. */
    public static Optional<IsoWeek> parse(String text) {
        if (text == null || text.length() != 8 || !SHAPE.matcher(text).matches()) {
            return Optional.empty();
        }
        int year = Integer.parseInt(text.substring(0, 4));
        int week = Integer.parseInt(text.substring(6, 8));
        if (year < EARLIEST_YEAR || year > LATEST_YEAR || week < 1 || week > weeksIn(year)) {
            return Optional.empty();
        }
        return Optional.of(new IsoWeek(year, week));
    }

    /** The week a date falls in. */
    public static IsoWeek containing(LocalDate date) {
        return new IsoWeek(
                date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /** The week whose Monday this is. */
    public static IsoWeek startingOn(LocalDate monday) {
        if (monday.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("a week starts on a Monday");
        }
        return containing(monday);
    }

    public LocalDate monday() {
        // The 4th of January is always in week 1 of its week-based year.
        return LocalDate.of(year, 1, 4)
                .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week)
                .with(DayOfWeek.MONDAY);
    }

    public LocalDate sunday() {
        return monday().plusDays(6);
    }

    public IsoWeek previous() {
        return containing(monday().minusDays(7));
    }

    public IsoWeek next() {
        return containing(monday().plusDays(7));
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(monday()) && !date.isAfter(sunday());
    }

    private static int weeksIn(int year) {
        // 28 December is always in the last week of its week-based year.
        return LocalDate.of(year, 12, 28).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    @Override
    public int compareTo(IsoWeek other) {
        return monday().compareTo(other.monday());
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "%04d-W%02d", year, week);
    }
}
