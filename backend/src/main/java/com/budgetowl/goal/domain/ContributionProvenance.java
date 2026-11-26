package com.budgetowl.goal.domain;

/**
 * How a contribution got here, as {@code PaymentProvenance} does for a debt payment.
 *
 * <p>The difference is not cosmetic: a {@link #CHECK_IN} contribution counts <b>on top of</b> the
 * month's plan in the left-over figure, where a {@link #USER_LOGGED} one counts towards it
 * (docs/features/weekly-check-in.md, decision 6).
 */
public enum ContributionProvenance {

    /** Somebody recorded it on the goal's page. Every row before slice 14. */
    USER_LOGGED,

    /** A completed weekly check-in put the week's surplus here. */
    CHECK_IN
}
