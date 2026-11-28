package com.budgetowl.debt.service;

import com.budgetowl.common.Money;
import java.time.YearMonth;
import java.util.Objects;

/**
 * One month of a projection: what was owed, what was paid, and how the payment split.
 *
 * <p>The row the screen draws, and the row a household can check with a calculator. Built by {@link
 * #of} so the two identities below hold by construction rather than by everyone remembering them:
 *
 * <ul>
 *   <li>{@code interest + principal = amount} — the interest is rounded and the principal takes the
 *       remainder, so no payment ever loses or invents a cent;
 *   <li>{@code balanceAfter = balance − principal} — which makes the chain of closing balances
 *       equal the opening balance minus the sum of the principals, exactly, for any number of
 *       months.
 * </ul>
 *
 * <p>Rounding happens once per month, on the interest, at scale 4 {@code HALF_EVEN} — and nowhere
 * else. Every other figure here is an exact sum or difference at that scale, so the figure on
 * screen and the figure in the chain of {@code balanceAfter} values are the same figure.
 *
 * @param monthNumber 1 for the first period, so {@code monthNumber} of the last row is {@code
 *     monthsLeft}
 * @param month the calendar month that period falls in
 * @param balance what was owed at the start of the period, before interest
 * @param amount what was paid at the end of the period. Constant for a single debt's projection; it
 *     moves month to month in a payoff ladder, which is the whole point of the roll-down
 * @param interest {@code round(balance × apr ÷ 100 ÷ 12, 4, HALF_EVEN)}
 * @param principal {@code amount − interest}. Negative when the payment does not cover the
 *     interest, which is what a balance that grows looks like in a row
 * @param balanceAfter {@code balance − principal}. Negative on the last row of a projection that
 *     overshoots, because overpaying is not clamped
 */
public record ProjectionMonth(
        int monthNumber,
        YearMonth month,
        Money balance,
        Money amount,
        Money interest,
        Money principal,
        Money balanceAfter) {

    public ProjectionMonth {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(interest, "interest");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(balanceAfter, "balanceAfter");
        if (monthNumber < 1) {
            throw new IllegalArgumentException(
                    "A projection starts at month 1, not " + monthNumber);
        }
        if (!interest.plus(principal).equals(amount)) {
            throw new IllegalArgumentException("interest + principal must equal the amount paid");
        }
        if (!balance.minus(principal).equals(balanceAfter)) {
            throw new IllegalArgumentException("balanceAfter must equal balance - principal");
        }
    }

    /** The only way these rows are built: the two identities are arithmetic, not assertions. */
    public static ProjectionMonth of(
            int monthNumber, YearMonth month, Money balance, Money amount, Money interest) {
        Money principal = amount.minus(interest);
        return new ProjectionMonth(
                monthNumber, month, balance, amount, interest, principal, balance.minus(principal));
    }

    /** True on the row where the debt reaches zero or goes past it. */
    public boolean clearsTheDebt() {
        return !balanceAfter.isPositive();
    }

    /** Amounts are never logged (docs/architecture/security-model.md). */
    @Override
    public String toString() {
        return "ProjectionMonth[monthNumber="
                + monthNumber
                + " month="
                + month
                + " amounts=<redacted>]";
    }
}
