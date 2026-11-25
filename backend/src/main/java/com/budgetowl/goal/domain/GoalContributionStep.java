package com.budgetowl.goal.domain;

import com.budgetowl.common.HouseholdOwned;
import com.budgetowl.common.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One step of a goal's monthly plan: <em>"from September, $165"</em> (decision 4).
 *
 * <p><b>Immutable once written.</b> A change to the plan replaces the steps at and after its month
 * with a new one, rather than editing an old step. That is what keeps a past month's left-over
 * figure from being rewritten: the step that was in force in August is still there, unchanged,
 * after September's raise.
 *
 * <p>No {@code version}: nothing updates a step, and the plan's writes are guarded by the goal's.
 */
@Entity
@Table(name = "goal_contribution_steps")
public class GoalContributionStep implements HouseholdOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "goal_id", nullable = false, updatable = false)
    private UUID goalId;

    @Column(name = "from_period", nullable = false, updatable = false)
    private LocalDate fromPeriod;

    @Column(name = "monthly_amount", nullable = false, updatable = false)
    private BigDecimal monthlyAmount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** For Hibernate and the factory. Does nothing and cannot throw. */
    protected GoalContributionStep() {
        // Hibernate, and from().
    }

    /**
     * @param amount zero or more, in the goal's currency. Zero is a valid step: it pauses the goal
     */
    public static GoalContributionStep from(Goal goal, YearMonth fromPeriod, Money amount) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(fromPeriod, "fromPeriod");
        Objects.requireNonNull(amount, "amount");
        if (!amount.currency().equals(goal.currency())) {
            throw new IllegalArgumentException("a plan step is in its goal's currency");
        }
        if (amount.isNegative()) {
            throw new IllegalArgumentException("a monthly contribution cannot be negative");
        }
        GoalContributionStep step = new GoalContributionStep();
        step.householdId = goal.householdId();
        step.goalId = goal.id();
        step.fromPeriod = fromPeriod.atDay(1);
        step.monthlyAmount = amount.amount();
        return step;
    }

    public UUID id() {
        return id;
    }

    @Override
    public UUID householdId() {
        return householdId;
    }

    public UUID goalId() {
        return goalId;
    }

    public YearMonth fromPeriod() {
        return YearMonth.from(fromPeriod);
    }

    /** No currency attached — it is the goal's. */
    public BigDecimal monthlyAmount() {
        return monthlyAmount;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** Ids and the month. The amount is a statement of disposable income. */
    @Override
    public String toString() {
        return "GoalContributionStep[id=" + id + ", fromPeriod=" + fromPeriod + "]";
    }
}
