package com.budgetowl.common.schedule;

/**
 * How often something recurs.
 *
 * <p><b>A small closed set, not a recurrence rule</b> (ADR-0032). Projection has to be total and
 * cheap: for any month, a bill must be able to say which dates it falls on without consulting
 * anything. RRULE brings a parser, a timezone model and an infinite tail of cases for a product
 * whose designs show five shapes.
 */
public enum ScheduleKind {

    /** One date a month, on {@code dayOfMonth}, clamped to the month's last day. */
    MONTHLY,

    /** One date a year, in {@code month} on {@code dayOfMonth}. */
    YEARLY,

    /** Every matching weekday in the month. */
    WEEKLY,

    /** Every other matching weekday, counted from {@code anchorDate}. */
    FORTNIGHTLY,

    /**
     * Unpredictable, and projects nothing.
     *
     * <p>Not a gap: a bill somebody cannot predict is still a bill they want listed, and the
     * designs show exactly that. Recording a status against a period creates the occurrence.
     */
    IRREGULAR
}
