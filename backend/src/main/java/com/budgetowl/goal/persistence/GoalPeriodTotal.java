package com.budgetowl.goal.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One goal's contributions for one month, added up. Withdrawals are included, so the total can be
 * negative.
 *
 * <p>A constructor-expression projection rather than {@code Object[]}: the column order cannot be
 * misread at the call site.
 *
 * @param period the first day of the month the contributions count towards
 */
public record GoalPeriodTotal(UUID goalId, LocalDate period, BigDecimal total) {

    /** Redacted: a month of somebody's saving. */
    @Override
    public String toString() {
        return "GoalPeriodTotal[goalId=" + goalId + ", period=" + period + "]";
    }
}
