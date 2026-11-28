package com.budgetowl.checkin.service;

import com.budgetowl.common.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * Next week's figure, from the plan (ADR-0040; docs/features/weekly-check-in.md, decision 1).
 *
 * <pre>
 * free(M)  = left(M) − unbudgeted(1st of M, min(week's Sunday, end of M)) − check-in debt payments in M
 * daily(d) = (free(M) − held) ÷ days from next Monday (or the 1st) to the end of M,  M = month(d)
 *          = left(M) ÷ days in M                       for a month that has not started by next Monday
 * base     = Σ daily(d) over the seven days of next week
 * held     = surplus when surplus &gt; 0 and free(M) ≥ surplus, else 0
 * </pre>
 *
 * <p><b>Rounding.</b> The seven days are summed exactly and {@code base} is rounded once,
 * half-even, to the currency's minor unit: a figure to spend is a figure in cents, and the designs'
 * own figures reconcile in cents ({@code 480 + 63 = 543}). Per day is {@code base ÷ 7} rounded
 * <b>down</b> to the minor unit and labelled "≈". Nothing is clamped: a negative figure is shown
 * negative, as {@code left} is.
 *
 * <p>No database, no clock, no household: a value. {@code WeeklyFigureTest} exercises it.
 */
final class WeeklyFigure {

    /** Precision for the intermediate division; far beyond any minor unit. */
    private static final int WORKING_SCALE = 12;

    private WeeklyFigure() {}

    /**
     * One month's terms. For a month that has not started by next Monday only {@code left} is read.
     */
    record MonthTerms(YearMonth month, Money left, Money unbudgeted, Money checkInDebtPayments) {

        MonthTerms {
            Objects.requireNonNull(month, "month");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(unbudgeted, "unbudgeted");
            Objects.requireNonNull(checkInDebtPayments, "checkInDebtPayments");
        }

        Money free() {
            return left.minus(unbudgeted).minus(checkInDebtPayments);
        }

        @Override
        public String toString() {
            return "MonthTerms[month=" + month + "]";
        }
    }

    /**
     * @param free {@code free(M)} for the month next week starts in
     * @param held what was set aside for the surplus's destination
     * @param base what next week can spend, before any rollover
     * @param rolledOver {@code base + surplus}, or null when there is no surplus to roll
     * @param basePerDay and {@code rolledPerDay}: ÷ 7, rounded down to the minor unit
     * @param surplusAllocatable whether a debt or a goal may take the surplus: {@code free(M) ≥
     *     surplus}. Only <em>roll over</em> is offered otherwise
     */
    record Result(
            Money free,
            Money held,
            Money base,
            Money rolledOver,
            Money basePerDay,
            Money rolledPerDay,
            boolean surplusAllocatable) {

        @Override
        public String toString() {
            return "WeeklyFigure.Result[]";
        }
    }

    /**
     * @param nextMonday the Monday after the check-in's week
     * @param months the month next Monday is in, then the following one when next week reaches it
     * @param surplus {@code weekTarget − weekSpending}, or null when no target was set
     */
    static Result of(LocalDate nextMonday, List<MonthTerms> months, Money surplus) {
        Objects.requireNonNull(nextMonday, "nextMonday");
        if (months.isEmpty() || !months.getFirst().month().equals(YearMonth.from(nextMonday))) {
            throw new IllegalArgumentException("the first month is the one next week starts in");
        }
        MonthTerms first = months.getFirst();
        Currency currency = first.left().currency();
        Money zero = Money.zero(currency);
        Money free = first.free();

        boolean positive = surplus != null && surplus.isPositive();
        boolean allocatable = positive && free.compareTo(surplus) >= 0;
        Money held = allocatable ? surplus : zero;

        LocalDate nextSunday = nextMonday.plusDays(6);
        BigDecimal total = BigDecimal.ZERO;
        for (MonthTerms month : months) {
            LocalDate from =
                    month.month().atDay(1).isAfter(nextMonday)
                            ? month.month().atDay(1)
                            : nextMonday;
            LocalDate end = month.month().atEndOfMonth();
            LocalDate to = end.isBefore(nextSunday) ? end : nextSunday;
            if (to.isBefore(from)) {
                continue;
            }
            long daysInWeek = ChronoUnit.DAYS.between(from, to) + 1;
            BigDecimal pool;
            long spread;
            if (month == first) {
                pool = free.minus(held).amount();
                spread = ChronoUnit.DAYS.between(nextMonday, end) + 1;
            } else {
                // A month that has not started by next Monday: nothing is spent in it yet, and its
                // whole plan is spread over its whole length.
                pool = month.left().amount();
                spread = month.month().lengthOfMonth();
            }
            total =
                    total.add(
                            pool.multiply(BigDecimal.valueOf(daysInWeek))
                                    .divide(
                                            BigDecimal.valueOf(spread),
                                            WORKING_SCALE,
                                            RoundingMode.HALF_EVEN));
        }
        int minor = minorUnit(currency);
        Money base = Money.of(total.setScale(minor, RoundingMode.HALF_EVEN), currency);
        Money rolled = positive ? base.plus(surplus) : null;
        return new Result(
                free,
                held,
                base,
                rolled,
                perDay(base, minor),
                rolled == null ? null : perDay(rolled, minor),
                allocatable);
    }

    /** {@code figure ÷ 7}, rounded down — towards negative infinity — to the minor unit. */
    static Money perDay(Money figure, int minor) {
        return Money.of(
                figure.amount().divide(BigDecimal.valueOf(7), minor, RoundingMode.FLOOR),
                figure.currency());
    }

    static int minorUnit(Currency currency) {
        int digits = currency.getDefaultFractionDigits();
        return digits < 0 ? Money.SCALE : Math.min(digits, Money.SCALE);
    }
}
