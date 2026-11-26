package com.budgetowl.goal.persistence;

import com.budgetowl.goal.domain.ContributionProvenance;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One goal's contributions for one month and one provenance, added up. Withdrawals are included, so
 * the total can be negative.
 *
 * <p>A constructor-expression projection rather than {@code Object[]}: the column order cannot be
 * misread at the call site.
 *
 * @param period the first day of the month the contributions count towards
 * @param provenance split out because a check-in's contributions count on top of the plan and
 *     everyone else's count towards it (docs/features/weekly-check-in.md, decision 6)
 */
public record GoalPeriodTotal(
        UUID goalId, LocalDate period, ContributionProvenance provenance, BigDecimal total) {

    /** Redacted: a month of somebody's saving. */
    @Override
    public String toString() {
        return "GoalPeriodTotal[goalId=" + goalId + ", period=" + period + "]";
    }
}
