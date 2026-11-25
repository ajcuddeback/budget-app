package com.budgetowl.goal.service;

import com.budgetowl.common.Money;
import java.time.YearMonth;
import java.util.Objects;

/**
 * One step of a monthly plan: from this month on, this much (decision 4).
 *
 * @param monthlyAmount zero or more. Zero is a valid step and means paused
 */
public record PlanStep(YearMonth fromPeriod, Money monthlyAmount) {

    public PlanStep {
        Objects.requireNonNull(fromPeriod, "fromPeriod");
        Objects.requireNonNull(monthlyAmount, "monthlyAmount");
        if (monthlyAmount.isNegative()) {
            throw new IllegalArgumentException("a monthly contribution cannot be negative");
        }
    }

    /** Redacted: the amount is a statement of disposable income. */
    @Override
    public String toString() {
        return "PlanStep[fromPeriod=" + fromPeriod + "]";
    }
}
