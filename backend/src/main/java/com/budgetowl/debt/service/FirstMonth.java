package com.budgetowl.debt.service;

import com.budgetowl.common.Money;
import java.util.Currency;
import java.util.Objects;

/**
 * What is already true of a projection's first month when that month is the current one (ADR-0043,
 * docs/features/debt-plan.md, The split).
 *
 * <p>A projection made mid-month plans <b>only what is still to come</b>:
 *
 * <ul>
 *   <li>{@link #interestBooked}: a payment recorded this month already carried the month's
 *       interest, so the balance includes it and month 1 is charged none;
 *   <li>{@link #alreadyPaid}: what was recorded against the debt this month — counted as paid, so
 *       month 1 plans the planned payment less this, and never less than nothing. Statement
 *       corrections are not payments and are not in it, and neither is a weekly check-in's surplus,
 *       which the household pays <em>on top of</em> the plan (weekly-check-in.md, decision 5).
 * </ul>
 *
 * <p>Overpaying the month is not carried forward: the balance already reflects it, and month 1
 * simply plans nothing more.
 */
public record FirstMonth(boolean interestBooked, Money alreadyPaid) {

    public FirstMonth {
        Objects.requireNonNull(alreadyPaid, "alreadyPaid");
        if (alreadyPaid.isNegative()) {
            throw new IllegalArgumentException("what was paid this month cannot be negative");
        }
    }

    /** A first month nothing has happened in yet: the ordinary recurrence. */
    public static FirstMonth untouched(Currency currency) {
        return new FirstMonth(false, Money.zero(currency));
    }

    /** What is still to come of {@code planned} this month: never negative. */
    public Money stillToPay(Money planned) {
        Money rest = planned.minus(alreadyPaid);
        return rest.isPositive() ? rest : Money.zero(planned.currency());
    }

    /** Amounts are never logged. */
    @Override
    public String toString() {
        return "FirstMonth[interestBooked=" + interestBooked + "]";
    }
}
