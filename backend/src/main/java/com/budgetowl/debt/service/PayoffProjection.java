package com.budgetowl.debt.service;

import com.budgetowl.common.Money;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * What a payoff plan projects: the ladder, when each rung clears, when the household is debt-free,
 * and what the whole thing costs in interest (docs/features/debt-plan.md).
 *
 * <p>Sealed, with {@link None} for the one monthly amount that produces no plan at all.
 */
public sealed interface PayoffProjection {

    /**
     * The ladder, in the order the strategy ranked it.
     *
     * @param rungs rung 1 first. Every active debt appears, including one that never clears
     * @param debtFreeMonth the latest of the per-debt payoff months, or <b>null</b> when at least
     *     one debt does not clear within {@link PayoffLadder#MAX_MONTHS}. Null rather than a
     *     comfortable guess: a plan that refuses to render because one debt is underwater is a plan
     *     nobody can use to fix it, but a debt-free date that ignores the underwater debt is a lie
     * @param firstWinMonth when rung 1 clears, or null if it does not. Reported on its own because
     *     it is snowball's whole argument
     * @param totalInterest the plan's total interest, per currency by construction — every debt in
     *     one ladder is in one currency, because a converted figure is never the input to stored
     *     arithmetic (ADR-0022)
     */
    record Ranked(
            List<Rung> rungs, YearMonth debtFreeMonth, YearMonth firstWinMonth, Money totalInterest)
            implements PayoffProjection {

        public Ranked {
            Objects.requireNonNull(totalInterest, "totalInterest");
            rungs = List.copyOf(rungs);
            if (rungs.isEmpty()) {
                throw new IllegalArgumentException("A ranked plan has at least one rung");
            }
        }

        /** True when every debt in the ladder reaches zero within the cap. */
        public boolean clearsEveryDebt() {
            return debtFreeMonth != null;
        }

        /** Redacted: {@code totalInterest} is an amount, and each rung carries five more. */
        @Override
        public String toString() {
            return "PayoffProjection.Ranked[rungs="
                    + rungs.size()
                    + ", debtFreeMonth="
                    + debtFreeMonth
                    + ", firstWinMonth="
                    + firstWinMonth
                    + "]";
        }
    }

    /**
     * No plan, and why.
     *
     * <p>Only ever {@link Projection.Reason#BELOW_MINIMUMS} today: a monthly amount below the sum
     * of the minimums is <b>accepted</b> — refused neither at the edge nor in the database, because
     * a household that genuinely cannot meet its minimums exists and {@code 422} is the wrong thing
     * to say to them — and produces no projection, because every figure computed from it would be a
     * fiction.
     */
    record None(Projection.Reason reason) implements PayoffProjection {

        public None {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * One debt's place in the ladder and what the plan does to it.
     *
     * @param rung 1 for the debt the surplus goes to
     * @param debt the debt as the ladder was given it
     * @param minimum the floor, paid every month until this rung clears
     * @param extra what this debt gets on top of its minimum <b>in the first month</b>: the
     *     surplus, which is zero for every rung but the first one at the start. <em>"$185 minimum +
     *     $260 extra"</em> is this pair. Negative in the one case where a debt owes less than its
     *     own minimum and clears in month 1 — nothing is ever paid more than it owes, so {@code
     *     minimum + extra} is always what was actually allocated
     * @param payoffMonth the month this debt reaches zero, or null when it does not within the cap
     * @param totalInterest this debt's interest over the plan
     * @param months the row-by-row ledger, which is where the roll-down is visible: the amount
     *     moves the month after the rung above clears
     */
    record Rung(
            int rung,
            LadderDebt debt,
            Money minimum,
            Money extra,
            YearMonth payoffMonth,
            Money totalInterest,
            List<ProjectionMonth> months) {

        public Rung {
            Objects.requireNonNull(debt, "debt");
            Objects.requireNonNull(minimum, "minimum");
            Objects.requireNonNull(extra, "extra");
            Objects.requireNonNull(totalInterest, "totalInterest");
            months = List.copyOf(months);
            if (rung < 1) {
                throw new IllegalArgumentException("Rungs are numbered from 1, not " + rung);
            }
        }

        /** <em>"$185 minimum + $260 extra"</em>, totalled. */
        public Money monthlyAmount() {
            return minimum.plus(extra);
        }

        public boolean clears() {
            return payoffMonth != null;
        }

        /**
         * Redacted, and this is the sharpest of the five: the default would print a balance, a
         * rate, a minimum, an extra, a total interest <b>and the debt's name</b>, which is the
         * field this slice guards hardest — <em>Nelnet</em> alone says this household has student
         * debt.
         */
        @Override
        public String toString() {
            return "PayoffProjection.Rung[rung="
                    + rung
                    + ", debtId="
                    + debt.id()
                    + ", payoffMonth="
                    + payoffMonth
                    + ", months="
                    + months.size()
                    + "]";
        }
    }
}
