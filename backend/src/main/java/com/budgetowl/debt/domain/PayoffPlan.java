package com.budgetowl.debt.domain;

import com.budgetowl.common.HouseholdOwned;
import com.budgetowl.common.Money;
import com.budgetowl.household.domain.Household;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * The two figures a household chose: the order to attack in, and what it puts towards debt each
 * month.
 *
 * <p><b>One row per household</b> (ADR-0026, {@code uq_payoff_plans_household}), which is why
 * {@code /api/debt-plan} is singular. Without the uniqueness a second row is a second answer to
 * "what is my strategy" and the endpoint would have to pick one — silently, and differently as rows
 * were added.
 *
 * <p><b>Nothing derived is stored here.</b> The order, the per-debt allocation, the payoff months,
 * the total interest and the debt-free month are computed on every read from this and the debts.
 * The strategy is the <em>selection</em>, not the computation: both named strategies are projected
 * whichever one is selected, because the comparison card is a product requirement rather than a
 * view of the selection.
 *
 * <p><b>Why the strategy is a {@code String} here.</b> {@code PayoffStrategy} lives with the
 * arithmetic in {@code ..service..}, where the ladder can be unit-tested with no database and no
 * Spring context — and an entity may not depend on a service package ({@code
 * ArchitectureTest.domain_is_plain_java}). The column is {@code text} either way, {@code
 * ck_payoff_plans_strategy} is the backstop, and {@code PayoffPlanService} is the only thing that
 * writes it.
 *
 * <p>{@code monthlyAmount} is deliberately <b>not</b> constrained to be at least the sum of the
 * minimums. A household that genuinely cannot meet its minimums exists, and refusing their plan
 * tells somebody in trouble that their situation is invalid input.
 */
@Entity
@Table(name = "payoff_plans")
public class PayoffPlan implements HouseholdOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "strategy", nullable = false)
    private String strategy;

    @Column(name = "monthly_amount", nullable = false)
    private BigDecimal monthlyAmount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** For Hibernate and the factory. Does nothing and cannot throw. */
    protected PayoffPlan() {
        // Hibernate, and of().
    }

    /**
     * A household's plan.
     *
     * @param monthlyAmount what it puts towards debt each month. Zero for a household that has not
     *     set one — rather than the sum of its minimums, which would be a figure nobody chose
     *     presented as one they had
     */
    public static PayoffPlan of(Household household, String strategy, Money monthlyAmount) {
        Objects.requireNonNull(household, "household");
        PayoffPlan plan = new PayoffPlan();
        plan.householdId = household.id();
        plan.attackIn(strategy);
        plan.commit(monthlyAmount);
        return plan;
    }

    public UUID id() {
        return id;
    }

    @Override
    public UUID householdId() {
        return householdId;
    }

    /** The selected strategy's name — {@code AVALANCHE}, {@code SNOWBALL} or {@code CUSTOM}. */
    public String strategy() {
        return strategy;
    }

    public BigDecimal monthlyAmount() {
        return monthlyAmount;
    }

    public Money monthlyAmount(Currency currency) {
        return Money.of(monthlyAmount, currency);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    public void attackIn(String strategyName) {
        String trimmed = Objects.requireNonNull(strategyName, "strategy").strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("a plan has a strategy");
        }
        this.strategy = trimmed;
    }

    /** Accepted below the minimums, and refused below zero: there is no negative plan. */
    public void commit(Money amount) {
        Objects.requireNonNull(amount, "monthlyAmount");
        if (amount.isNegative()) {
            throw new IllegalArgumentException("a monthly amount cannot be negative");
        }
        this.monthlyAmount = amount.amount();
    }

    /** Ids and the strategy. The monthly amount is a statement of disposable income. */
    @Override
    public String toString() {
        return "PayoffPlan[id=" + id + ", strategy=" + strategy + "]";
    }
}
