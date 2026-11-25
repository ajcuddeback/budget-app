package com.budgetowl.goal.service;

import com.budgetowl.common.Money;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Everything one goal's figures are derived from, and the <b>one</b> definition of what the goal
 * contributes to a month.
 *
 * <p>The projection on {@code /goals}, this month's line on a goal, and the fifth term of the
 * period summary all read {@link #monthContribution(YearMonth)} and {@link #planned(YearMonth)}
 * from an instance of this record. That is the lesson of {@code docs/memory/gotchas.md}'s "a figure
 * composed from two services can fall between their definitions": the left-over figure and the goal
 * page cannot disagree about a month, because there is one method that answers and both of them
 * call it.
 *
 * <p>No database, no clock, no household: a value. {@code GoalProjectionTest} exercises it
 * directly.
 *
 * @param target positive
 * @param targetMonth the month the household wants it by, or null for none
 * @param startingAmount what was already set aside when tracking started. Counts towards {@link
 *     #saved()}; never towards any month's contribution
 * @param steps the plan, any order; held oldest first
 * @param recorded what was recorded against each month, withdrawals included. A month that is
 *     absent recorded nothing
 */
public record GoalLedger(
        Money target,
        Money startingAmount,
        YearMonth targetMonth,
        List<PlanStep> steps,
        Map<YearMonth, Money> recorded) {

    public GoalLedger {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(startingAmount, "startingAmount");
        Currency currency = target.currency();
        if (!startingAmount.currency().equals(currency)) {
            throw new IllegalArgumentException("a goal's figures are in one currency");
        }
        List<PlanStep> sorted = new ArrayList<>(steps);
        sorted.sort(Comparator.comparing(PlanStep::fromPeriod));
        for (PlanStep step : sorted) {
            if (!step.monthlyAmount().currency().equals(currency)) {
                throw new IllegalArgumentException("a goal's plan is in its own currency");
            }
        }
        steps = List.copyOf(sorted);
        TreeMap<YearMonth, Money> byMonth = new TreeMap<>();
        for (Map.Entry<YearMonth, Money> month : recorded.entrySet()) {
            if (!month.getValue().currency().equals(currency)) {
                throw new IllegalArgumentException("a goal's contributions are in its currency");
            }
            byMonth.put(month.getKey(), month.getValue());
        }
        recorded = java.util.Collections.unmodifiableSortedMap(byMonth);
    }

    public Currency currency() {
        return target.currency();
    }

    /** The month the household wants it by, if it named one. */
    public Optional<YearMonth> deadline() {
        return Optional.ofNullable(targetMonth);
    }

    /** {@code startingAmount + Σ contributions}, withdrawals included and never clamped. */
    public Money saved() {
        Money saved = startingAmount;
        for (Money month : recorded.values()) {
            saved = saved.plus(month);
        }
        return saved;
    }

    /** What had been saved when {@code period} began: contributions labelled before it only. */
    public Money savedBefore(YearMonth period) {
        Money saved = startingAmount;
        for (Map.Entry<YearMonth, Money> month : recorded.entrySet()) {
            if (month.getKey().isBefore(period)) {
                saved = saved.plus(month.getValue());
            }
        }
        return saved;
    }

    /** What was recorded against {@code period}, withdrawals included. Zero for none. */
    public Money recorded(YearMonth period) {
        return recorded.getOrDefault(period, Money.zero(currency()));
    }

    /** {@code saved ≥ target}. Derived, never stored (decision 7). */
    public boolean isReached() {
        return saved().compareTo(target) >= 0;
    }

    /** Whether {@code period} began with the goal already reached. */
    public boolean beganReached(YearMonth period) {
        return savedBefore(period).compareTo(target) >= 0;
    }

    /**
     * The plan's step in force for {@code period}: the latest at or before it, and zero before the
     * first. This is what the household chose, whatever the goal's state.
     */
    public Money inForce(YearMonth period) {
        Money amount = Money.zero(currency());
        for (PlanStep step : steps) {
            if (step.fromPeriod().isAfter(period)) {
                break;
            }
            amount = step.monthlyAmount();
        }
        return amount;
    }

    /**
     * What the plan asks of {@code period}: the step in force, <b>or zero for a month that began
     * with the goal already reached</b>. Reaching a goal mid-month never refunds that month; it
     * stops counting from the next.
     */
    public Money planned(YearMonth period) {
        return beganReached(period) ? Money.zero(currency()) : inForce(period);
    }

    /**
     * <b>The rule</b>: what this goal takes out of {@code period}'s left-over figure.
     *
     * <p>{@code max(planned, recorded)}. Recording £50 against a £110 plan still counts £110,
     * because the other £60 is still spoken for; a £200 top-up counts £200, because money moved
     * towards a goal is not free to spend; and a withdrawal never raises it, because the spending a
     * withdrawal pays for is not in the left-over figure either.
     */
    public Money monthContribution(YearMonth period) {
        Money planned = planned(period);
        Money recordedThen = recorded(period);
        return recordedThen.compareTo(planned) > 0 ? recordedThen : planned;
    }

    /** What is still to come this month: {@code max(planned − recorded, 0)}. */
    public Money stillToCome(YearMonth period) {
        Money gap = planned(period).minus(recorded(period));
        return gap.isNegative() ? Money.zero(currency()) : gap;
    }

    /**
     * The plan with "from {@code fromPeriod}, this much" applied: every step at or after it
     * replaced by one new step.
     *
     * <p><b>The one place a plan change is defined.</b> The plan {@code PUT} stores what this
     * returns and the preview projects it, so a preview cannot disagree with what saving reports. A
     * step that would repeat the amount already in force is not added, so sending the same
     * instruction twice leaves the same plan.
     */
    public List<PlanStep> stepsWith(YearMonth fromPeriod, Money monthlyAmount) {
        List<PlanStep> kept = new ArrayList<>();
        for (PlanStep step : steps) {
            if (step.fromPeriod().isBefore(fromPeriod)) {
                kept.add(step);
            }
        }
        Money before = kept.isEmpty() ? Money.zero(currency()) : kept.getLast().monthlyAmount();
        if (before.compareTo(monthlyAmount) != 0) {
            kept.add(new PlanStep(fromPeriod, monthlyAmount));
        }
        return List.copyOf(kept);
    }

    /** This ledger with another plan, for a preview and for {@code GoalProjection}'s search. */
    public GoalLedger withSteps(List<PlanStep> otherSteps) {
        return new GoalLedger(target, startingAmount, targetMonth, otherSteps, recorded);
    }

    /** The month after which the plan no longer changes, or empty for a plan with no steps. */
    Optional<YearMonth> lastStepMonth() {
        return steps.isEmpty() ? Optional.empty() : Optional.of(steps.getLast().fromPeriod());
    }

    /** Redacted: a target, a starting amount and a month-by-month record of somebody's saving. */
    @Override
    public String toString() {
        return "GoalLedger[steps=" + steps.size() + ", months=" + recorded.size() + "]";
    }
}
