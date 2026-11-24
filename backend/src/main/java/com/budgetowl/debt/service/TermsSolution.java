package com.budgetowl.debt.service;

import java.util.Objects;

/**
 * What {@link DebtTermsSolver#solve} came back with: the filled-in term set, or a refusal naming
 * the field at fault.
 *
 * <p>Sealed, so a caller handles both and a third outcome is a compile error.
 */
public sealed interface TermsSolution {

    /**
     * The solve succeeded.
     *
     * <p>Note that {@code projection} may be {@link Projection.None} with {@link
     * Projection.Reason#NEVER_CLEARS} while this is still a {@code Solved}: a payment that does not
     * cover the first month's interest is a legitimate term set with no horizon in it. The terms
     * are accepted, {@code monthsLeft} and {@code payoffMonth} stay absent, and the reason is
     * reported beside them. Refusing to record a debt because its minimum is too small tells
     * somebody in trouble that their situation is invalid input.
     *
     * @param terms the input with the solved field filled in — except in the {@code never-clears}
     *     case above, where the horizon stays absent because there is none
     * @param solvedField which field was derived, so the UI can label that cell {@code Solved} and
     *     no other
     * @param projection the ledger the terms imply
     * @param bisectionSteps how many times the recurrence was probed to find the answer: 0 for a
     *     horizon solve, which is one run of the recurrence and no search. Exposed because the
     *     bound on it is a threat-model control (docs/features/debt-plan.md), and a control that is
     *     not observable is a control nobody can test
     */
    record Solved(DebtTerms terms, TermField solvedField, Projection projection, int bisectionSteps)
            implements TermsSolution {

        public Solved {
            Objects.requireNonNull(terms, "terms");
            Objects.requireNonNull(solvedField, "solvedField");
            Objects.requireNonNull(projection, "projection");
            if (bisectionSteps < 0 || bisectionSteps > DebtTermsSolver.MAX_BISECTION_STEPS) {
                throw new IllegalArgumentException(
                        "A solve takes between 0 and "
                                + DebtTermsSolver.MAX_BISECTION_STEPS
                                + " bisection steps, not "
                                + bisectionSteps);
            }
        }

        /**
         * Redacted: {@code terms} carries the balance, the rate and the payment, and {@code
         * projection} carries a balance per month. Both redact their own {@code toString}; a record
         * holding them does not inherit that.
         */
        @Override
        public String toString() {
            return "TermsSolution.Solved[solvedField="
                    + solvedField
                    + ", bisectionSteps="
                    + bisectionSteps
                    + "]";
        }
    }

    /**
     * There is no answer: a horizon the balance cannot reach at any non-negative rate, a negative
     * payment, a payoff month in the past, or a search that hit one of the caps. The web edge turns
     * this into {@code 422 terms-unsolvable} naming {@link #field()}.
     *
     * @param detail developer-facing, for logs and for the problem's detail. <b>Never carries an
     *     amount</b> — the figure that failed is somebody's debt
     */
    record Unsolvable(TermField field, String detail) implements TermsSolution {

        public Unsolvable {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(detail, "detail");
        }
    }
}
