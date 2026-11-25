package com.budgetowl.goal.service;

import com.budgetowl.common.Money;
import java.time.YearMonth;

/**
 * Every figure a goal reports that is not a column: derived on read, stored nowhere.
 *
 * @param saved {@code startingAmount + Σ contributions}, never clamped: an over-funded goal reads
 *     over its target and a large withdrawal reads negative
 * @param remaining {@code target − saved}. Zero or negative once reached
 * @param overdue a target month has passed and the goal is not reached. Derived, as a bill's is
 * @param monthlyContribution the plan's step in force this month, as the household set it
 */
public record GoalFigures(
        Money saved,
        Money remaining,
        boolean reached,
        boolean overdue,
        ThisPeriod thisPeriod,
        Money monthlyContribution,
        Projection projection) {

    /**
     * This month's line.
     *
     * @param planned what the plan asks of this month — zero if the month began with the goal
     *     reached
     * @param recorded what has been recorded against it so far, withdrawals included
     * @param stillToCome {@code max(planned − recorded, 0)}
     */
    public record ThisPeriod(YearMonth period, Money planned, Money recorded, Money stillToCome) {

        /** Redacted. */
        @Override
        public String toString() {
            return "ThisPeriod[period=" + period + "]";
        }
    }

    /**
     * When the goal gets there, and what it would take to get there on time.
     *
     * @param projectedMonth the first month the walk reaches the target at the plan in force, or
     *     null with {@code projectedReason}
     * @param requiredMonthly the smallest plan from this month on, <b>at the currency's minor-unit
     *     scale</b>, that reaches the target in or before the target month; or null with {@code
     *     requiredReason}
     */
    public record Projection(
            YearMonth projectedMonth,
            ProjectionReason projectedReason,
            Money requiredMonthly,
            ProjectionReason requiredReason) {

        /** Redacted: the required figure is an amount. */
        @Override
        public String toString() {
            return "Projection[projectedMonth="
                    + projectedMonth
                    + ", projectedReason="
                    + projectedReason
                    + ", requiredReason="
                    + requiredReason
                    + "]";
        }
    }

    /** Redacted: a goal's whole position in one line otherwise. */
    @Override
    public String toString() {
        return "GoalFigures[reached=" + reached + ", overdue=" + overdue + "]";
    }
}
