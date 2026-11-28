package com.budgetowl.goal.service;

/**
 * Why a projected figure is absent. <b>Not an error</b>: each travels beside a {@code null} on a
 * goal that was accepted and stored, and each has its own sentence on screen. A code is part of the
 * API contract, as an {@code ErrorCode} is.
 */
public enum ProjectionReason {

    /** Nothing from this month on adds to the goal: no plan, a paused one, or one that stops. */
    NO_CONTRIBUTION("no-contribution"),

    /** At this plan, more than the 600-month horizon away — or a target month past it. */
    BEYOND_HORIZON("beyond-horizon"),

    /** Already there. */
    REACHED("reached"),

    /** No month was named, so there is nothing to work backwards from. */
    NO_TARGET_MONTH("no-target-month"),

    /** The month named has gone and the goal is not reached: it is overdue. */
    TARGET_MONTH_PASSED("target-month-passed"),

    /**
     * No monthly amount gets there by the month named. Only possible when that month is this one
     * and this month's plan does not count, because the month began with the goal reached and a
     * withdrawal has since taken it back below. Recording the rest directly still works.
     */
    NOT_REACHABLE_BY_TARGET_MONTH("not-reachable-by-target-month");

    private final String code;

    ProjectionReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
