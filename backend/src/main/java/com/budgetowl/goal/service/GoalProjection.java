package com.budgetowl.goal.service;

import com.budgetowl.common.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;

/**
 * A goal's figures: how far along it is, when it gets there at the plan in force, and what it would
 * take to get there by the month named ({@code docs/features/goals.md}, Projection).
 *
 * <p><b>The walk is the definition.</b> Start from {@code saved}; add what is still to come this
 * month; add each later month's plan; the projected month is the first at which the running figure
 * reaches the target. The required monthly amount is <em>searched for with that same walk</em>
 * rather than computed by a second formula — the closed form only proposes a candidate, and the
 * walk decides. Two implementations of one rule drift; the debt preview taught that.
 *
 * <p><b>Bounded at {@link #HORIZON_MONTHS}</b>, the debt solver's horizon. A plan of {@code 0.0001}
 * against a target of {@code 999999999999999} would otherwise walk for about 10^19 months on every
 * list read (threat model, attack 5). Past the cap the answer is {@code beyond-horizon}.
 *
 * <p>No interest and no growth: a goal is a sum of what was put in. Pure arithmetic over a {@link
 * GoalLedger}, with "now" passed in, so it is tested with no database and no clock.
 */
public final class GoalProjection {

    /** Fifty years of months. The debt solver's horizon, for the same reason. */
    public static final int HORIZON_MONTHS = 600;

    private GoalProjection() {}

    /**
     * Every derived figure for a goal, as of {@code current}.
     *
     * @param current the current period: the calendar month of today on the server's clock
     */
    public static GoalFigures figures(GoalLedger ledger, YearMonth current) {
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(current, "current");
        Money saved = ledger.saved();
        boolean reached = ledger.isReached();
        Optional<YearMonth> deadline = ledger.deadline();
        boolean overdue = !reached && deadline.filter(month -> month.isBefore(current)).isPresent();

        return new GoalFigures(
                saved,
                ledger.target().minus(saved),
                reached,
                overdue,
                new GoalFigures.ThisPeriod(
                        current,
                        ledger.planned(current),
                        ledger.recorded(current),
                        ledger.stillToCome(current)),
                ledger.inForce(current),
                reached ? reachedProjection() : projection(ledger, current));
    }

    /**
     * The scale an amount somebody will type is given at: two places for GBP, none for JPY, three
     * for KWD. A currency with no fixed minor unit keeps the storage scale, as {@code
     * DebtTermsSolver.interestFor} does: rounding somebody's money to a whole unit on the strength
     * of a missing lookup is worse than carrying four decimals.
     */
    public static int minorUnitScale(Currency currency) {
        int digits = currency.getDefaultFractionDigits();
        return digits < 0 ? Money.SCALE : Math.min(digits, Money.SCALE);
    }

    // ------------------------------------------------------------------------------- the walk

    /** Where a walk ended. */
    sealed interface Walk {

        /** The running figure reached the target in this month. */
        record Reaches(YearMonth month) implements Walk {}

        /** The plan stops adding anything, and the target is still ahead. */
        record Never() implements Walk {}

        /** Still short after the number of months the walk was allowed. */
        record OutOfMonths() implements Walk {}
    }

    /**
     * Walks the ledger's own plan forward from {@code current}, for at most {@code months} months
     * counting {@code current} as the first.
     *
     * <p>The current month adds only what is still to come — {@code max(planned − recorded, 0)} —
     * because what was already recorded is in {@code saved}. Every later month adds the step in
     * force. Once no later step can change the amount and it is zero, the walk stops: nothing
     * further will ever arrive, which is {@code no-contribution} rather than a horizon.
     */
    static Walk walk(GoalLedger ledger, YearMonth current, int months) {
        Money target = ledger.target();
        Money running = ledger.saved();
        Optional<YearMonth> lastStep = ledger.lastStepMonth();
        for (int i = 0; i < months; i++) {
            YearMonth month = current.plusMonths(i);
            running = running.plus(i == 0 ? ledger.stillToCome(current) : ledger.inForce(month));
            if (running.compareTo(target) >= 0) {
                return new Walk.Reaches(month);
            }
            boolean planStillChanges = lastStep.filter(last -> last.isAfter(month)).isPresent();
            if (!planStillChanges && !ledger.inForce(month.plusMonths(1)).isPositive()) {
                return new Walk.Never();
            }
        }
        return new Walk.OutOfMonths();
    }

    // -------------------------------------------------------------------------------- internals

    private static GoalFigures.Projection reachedProjection() {
        return new GoalFigures.Projection(
                null, ProjectionReason.REACHED, null, ProjectionReason.REACHED);
    }

    private static GoalFigures.Projection projection(GoalLedger ledger, YearMonth current) {
        YearMonth projectedMonth = null;
        ProjectionReason projectedReason = null;
        switch (walk(ledger, current, HORIZON_MONTHS)) {
            case Walk.Reaches reaches -> projectedMonth = reaches.month();
            case Walk.Never ignored -> projectedReason = ProjectionReason.NO_CONTRIBUTION;
            case Walk.OutOfMonths ignored -> projectedReason = ProjectionReason.BEYOND_HORIZON;
        }

        Money requiredMonthly = null;
        ProjectionReason requiredReason = null;
        Optional<YearMonth> deadline = ledger.deadline();
        if (deadline.isEmpty()) {
            requiredReason = ProjectionReason.NO_TARGET_MONTH;
        } else if (deadline.get().isBefore(current)) {
            requiredReason = ProjectionReason.TARGET_MONTH_PASSED;
        } else {
            long months = ChronoUnit.MONTHS.between(current, deadline.get()) + 1;
            if (months > HORIZON_MONTHS) {
                requiredReason = ProjectionReason.BEYOND_HORIZON;
            } else {
                requiredMonthly = requiredMonthly(ledger, current, (int) months).orElse(null);
                if (requiredMonthly == null) {
                    requiredReason = ProjectionReason.NOT_REACHABLE_BY_TARGET_MONTH;
                }
            }
        }
        return new GoalFigures.Projection(
                projectedMonth, projectedReason, requiredMonthly, requiredReason);
    }

    /**
     * The smallest plan, from {@code current} on and at the currency's minor-unit scale, that
     * reaches the target within {@code months} months under {@link #walk}.
     *
     * <p>The closed form — {@code ⌈(target − savedBefore(C)) ÷ n⌉}, or {@code ⌈remaining ÷ (n −
     * 1)⌉} once this month's slot is already filled — proposes a candidate. <b>The walk accepts
     * it</b> only if it reaches the target and one minor unit less does not. Otherwise, which the
     * formula should never cause, a bisection over the walk finds it. Either way the answer is the
     * walk's.
     *
     * <p>Rounded <b>up</b>, explicitly. Rounding to nearest sometimes leaves the household a cent
     * short in the final month, and the projection would then put the goal a month after the one
     * they asked for.
     */
    static Optional<Money> requiredMonthly(GoalLedger ledger, YearMonth current, int months) {
        Currency currency = ledger.currency();
        int scale = minorUnitScale(currency);
        BigDecimal unit = BigDecimal.ONE.movePointLeft(scale);
        BigDecimal candidate =
                ceilingDivide(ledger.target().minus(ledger.savedBefore(current)), months, scale);
        if (candidate.compareTo(ledger.recorded(current).amount()) <= 0 && months > 1) {
            candidate = ceilingDivide(ledger.target().minus(ledger.saved()), months - 1, scale);
        }

        if (reachesWithin(ledger, current, months, candidate)
                && (candidate.signum() == 0
                        || !reachesWithin(ledger, current, months, candidate.subtract(unit)))) {
            return Optional.of(Money.of(candidate, currency));
        }
        return bisect(ledger, current, months, scale);
    }

    /** The fallback search: the smallest whole number of minor units the walk accepts. */
    private static Optional<Money> bisect(
            GoalLedger ledger, YearMonth current, int months, int scale) {
        Currency currency = ledger.currency();
        BigDecimal remaining = ledger.target().minus(ledger.saved()).amount();
        // Enough to cover everything in this month alone, over and above whatever this month
        // already recorded (the walk only adds what is still to come of it). If this fails, no
        // amount succeeds.
        BigDecimal recordedNow = ledger.recorded(current).amount().max(BigDecimal.ZERO);
        BigDecimal high = remaining.add(recordedNow).setScale(scale, RoundingMode.CEILING);
        if (!reachesWithin(ledger, current, months, high)) {
            return Optional.empty();
        }
        // BigDecimal rather than a long count of minor units: a target near the column's limit with
        // a large withdrawal recorded is more minor units than a long holds at scale 4.
        BigDecimal low = BigDecimal.ZERO.setScale(scale);
        BigDecimal unit = BigDecimal.ONE.movePointLeft(scale);
        BigDecimal two = BigDecimal.valueOf(2);
        while (low.compareTo(high) < 0) {
            BigDecimal middle = low.add(high.subtract(low).divide(two, scale, RoundingMode.FLOOR));
            if (reachesWithin(ledger, current, months, middle)) {
                high = middle;
            } else {
                low = middle.add(unit);
            }
        }
        return Optional.of(Money.of(high, currency));
    }

    /**
     * Whether a plan of {@code amount} from {@code current} on gets there within {@code months}.
     */
    private static boolean reachesWithin(
            GoalLedger ledger, YearMonth current, int months, BigDecimal amount) {
        if (amount.signum() < 0) {
            return false;
        }
        GoalLedger planned =
                ledger.withSteps(ledger.stepsWith(current, Money.of(amount, ledger.currency())));
        return walk(planned, current, months) instanceof Walk.Reaches;
    }

    /** {@code ⌈amount ÷ parts⌉} at {@code scale}; zero for nothing left to find. */
    private static BigDecimal ceilingDivide(Money amount, int parts, int scale) {
        if (!amount.isPositive()) {
            return BigDecimal.ZERO.setScale(scale);
        }
        return amount.amount().divide(BigDecimal.valueOf(parts), scale, RoundingMode.CEILING);
    }
}
