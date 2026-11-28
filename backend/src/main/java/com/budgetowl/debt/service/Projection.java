package com.budgetowl.debt.service;

import com.budgetowl.common.Money;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * What the recurrence produced: either a month-by-month ledger that reaches zero, or no projection
 * and the reason there is none (docs/features/debt-plan.md).
 *
 * <p>Sealed so a {@code switch} over it is exhaustive and a fourth outcome is a compile error
 * rather than a silently unhandled case. {@link None} is a first-class answer, not an error: a
 * card's real minimum is a percentage of the balance, so a household entering last month's flat
 * minimum hits {@link Reason#NEVER_CLEARS} legitimately and their terms are still accepted and
 * stored.
 */
public sealed interface Projection {

    /** Why there is no projection. The {@code code} is the reason code the API returns. */
    enum Reason {

        /**
         * {@code payment ≤ interest(1)}: the balance grows every month and there is no horizon to
         * solve for. The common real case, not an exotic one, and never an exception.
         */
        NEVER_CLEARS("never-clears"),

        /**
         * The balance does clear, but not within {@link DebtTermsSolver#MAX_MONTHS} months. A
         * threat-model cap (docs/features/debt-plan.md, Security considerations): the refusal is
         * what keeps one request bounded, so it is never a longer loop.
         */
        EXCEEDS_MONTH_CAP("terms-unsolvable"),

        /**
         * A plan's monthly amount is below the sum of the minimums. Accepted and reported rather
         * than refused — a household that genuinely cannot meet its minimums exists, and {@code
         * 422} is the wrong thing to say to them — but every projection computed from such an
         * amount would be a fiction.
         */
        BELOW_MINIMUMS("plan-below-minimums");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** True when there are months to show. */
    boolean clears();

    /**
     * The debt reaches zero.
     *
     * @param months every period, in order, the last of which is the one that clears it
     * @param payoffMonth the month of the last row. A {@link YearMonth} everywhere, because monthly
     *     arithmetic cannot produce a day
     * @param totalInterest the sum of the rows' interest — derived, never stored
     */
    record Cleared(List<ProjectionMonth> months, YearMonth payoffMonth, Money totalInterest)
            implements Projection {

        public Cleared {
            Objects.requireNonNull(payoffMonth, "payoffMonth");
            Objects.requireNonNull(totalInterest, "totalInterest");
            months = List.copyOf(months);
            if (months.isEmpty()) {
                throw new IllegalArgumentException("A cleared projection has at least one month");
            }
        }

        /** The horizon this projection implies. */
        public Horizon horizon() {
            return Horizon.ofMonths(months.size());
        }

        public int monthsLeft() {
            return months.size();
        }

        @Override
        public boolean clears() {
            return true;
        }

        /**
         * Redacted: {@code totalInterest} is an amount and every {@code ProjectionMonth} carries a
         * balance. {@code ProjectionMonth} redacts its own; a record holding a list of them does
         * not inherit that, because the generated {@code toString} prints the list.
         */
        @Override
        public String toString() {
            return "Projection.Cleared[months="
                    + months.size()
                    + ", payoffMonth="
                    + payoffMonth
                    + "]";
        }
    }

    /**
     * No projection, and why. {@code monthsLeft}, {@code payoffMonth} and {@code totalInterestLeft}
     * are all {@code null} to the API, with this reason beside them.
     */
    record None(Reason reason) implements Projection {

        public None {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public boolean clears() {
            return false;
        }
    }
}
