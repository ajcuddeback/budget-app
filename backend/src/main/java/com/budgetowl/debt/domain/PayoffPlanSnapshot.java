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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * What the plan projected at the moment it last changed, per currency.
 *
 * <p>This is what <em>"4 months earlier than last month's plan"</em> compares against, and it
 * <b>cannot be backfilled</b>: a projection computed today from today's debts is today's answer,
 * not last month's. So it is written when the plan changes — strategy, monthly amount or custom
 * order — and never updated afterwards, which is why there is no {@code version} column and no
 * setter here. Slice 10 writes the snapshot; the screen that renders the delta is a later slice.
 *
 * <p>The plan is copied rather than referenced for the same reason: a foreign key to the live row
 * would show today's figures under yesterday's date.
 *
 * <p>{@code debtFreeMonth} and {@code totalInterest} are both present or both absent ({@code
 * ck_payoff_plan_snapshots_projection_is_whole}). Absent is the honest answer when the projection
 * could not be computed — a debt that never clears, or an amount below the minimums — and a zero
 * would read as "debt-free, this month".
 */
@Entity
@Table(name = "payoff_plan_snapshots")
public class PayoffPlanSnapshot implements HouseholdOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    /**
     * Set from the application's clock rather than left to the column's {@code now()} default.
     *
     * <p>{@code now()} is transaction-start time, so every row a transaction writes shares it —
     * which is what {@code ux_payoff_plan_snapshots_household_taken_at_currency} relies on to tell
     * "two currencies, one plan change" from "the same currency twice". Passing one instant in
     * keeps that true and keeps the value testable with a fixed clock.
     */
    @Column(name = "taken_at", nullable = false, updatable = false)
    private Instant takenAt;

    @Column(name = "strategy", nullable = false, updatable = false)
    private String strategy;

    @Column(name = "monthly_amount", nullable = false, updatable = false)
    private BigDecimal monthlyAmount;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Column(name = "debt_free_month", updatable = false)
    private LocalDate debtFreeMonth;

    @Column(name = "total_interest", updatable = false)
    private BigDecimal totalInterest;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** For Hibernate and the factory. Does nothing and cannot throw. */
    protected PayoffPlanSnapshot() {
        // Hibernate, and of().
    }

    /**
     * @param debtFreeMonth and {@code totalInterest}: both null when there was no projection to
     *     snapshot. Passing one of the two is a programming error rather than a request somebody
     *     made, so it throws
     */
    public static PayoffPlanSnapshot of(
            Household household,
            Instant takenAt,
            String strategy,
            Money monthlyAmount,
            YearMonth debtFreeMonth,
            Money totalInterest) {
        Objects.requireNonNull(household, "household");
        Objects.requireNonNull(monthlyAmount, "monthlyAmount");
        if ((debtFreeMonth == null) != (totalInterest == null)) {
            throw new IllegalArgumentException(
                    "a snapshot carries a debt-free month and a total interest, or neither");
        }
        PayoffPlanSnapshot snapshot = new PayoffPlanSnapshot();
        snapshot.householdId = household.id();
        snapshot.takenAt = Objects.requireNonNull(takenAt, "takenAt");
        snapshot.strategy = Objects.requireNonNull(strategy, "strategy");
        snapshot.monthlyAmount = monthlyAmount.amount();
        snapshot.currency = monthlyAmount.currency().getCurrencyCode();
        snapshot.debtFreeMonth = debtFreeMonth == null ? null : debtFreeMonth.atDay(1);
        snapshot.totalInterest = totalInterest == null ? null : totalInterest.amount();
        return snapshot;
    }

    public UUID id() {
        return id;
    }

    @Override
    public UUID householdId() {
        return householdId;
    }

    public Instant takenAt() {
        return takenAt;
    }

    public String strategy() {
        return strategy;
    }

    public BigDecimal monthlyAmount() {
        return monthlyAmount;
    }

    public String currency() {
        return currency;
    }

    public Optional<YearMonth> debtFreeMonth() {
        return Optional.ofNullable(debtFreeMonth).map(YearMonth::from);
    }

    public Optional<BigDecimal> totalInterest() {
        return Optional.ofNullable(totalInterest);
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** Ids, a time and a currency. Not the amount, and not the date it projected. */
    @Override
    public String toString() {
        return "PayoffPlanSnapshot[id="
                + id
                + ", takenAt="
                + takenAt
                + ", currency="
                + currency
                + "]";
    }
}
