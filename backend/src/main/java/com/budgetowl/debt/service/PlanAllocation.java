package com.budgetowl.debt.service;

import com.budgetowl.common.Money;
import java.time.YearMonth;
import java.util.Objects;

/**
 * What the plan does to one debt: where it sits, what it pays, and when that clears it.
 *
 * <p>This is the figure behind <em>from your plan</em> — the third label in the terms panel, and
 * the one that is neither entered nor solved. {@link #monthlyAmount()} is what the debt's own
 * projection uses as its payment when the household has not entered one.
 *
 * @param rung 1 for the debt the surplus goes to
 * @param minimum the floor, paid every month until this rung clears
 * @param extra what this rung gets on top of its minimum <b>in the first month</b>. <em>"$185
 *     minimum + $260 extra"</em> is this pair. It can be negative in one case — a debt that owes
 *     less than its own minimum and clears in month 1 — because a rung is never paid more than it
 *     owes, so {@code minimum + extra} is always what was really allocated (ADR-0036)
 * @param payoffMonth when this debt reaches zero under the plan, or null when it does not within
 *     the cap. Null rather than a comfortable guess
 */
public record PlanAllocation(
        int rung, Money minimum, Money extra, YearMonth payoffMonth, Money totalInterest) {

    public PlanAllocation {
        Objects.requireNonNull(minimum, "minimum");
        Objects.requireNonNull(extra, "extra");
        Objects.requireNonNull(totalInterest, "totalInterest");
    }

    /**
     * The allocation the plan makes in the first month, which is what the household will act on.
     */
    public Money monthlyAmount() {
        return minimum.plus(extra);
    }

    /** Amounts are never logged. */
    @Override
    public String toString() {
        return "PlanAllocation[rung=" + rung + "]";
    }
}
