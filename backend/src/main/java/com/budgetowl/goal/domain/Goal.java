package com.budgetowl.goal.domain;

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
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Money a household is putting aside for something ({@code docs/features/goals.md}).
 *
 * <p><b>There is no {@code saved}, {@code remaining} or {@code reached} here</b>, and that is
 * deliberate. All three are derived on read from {@link #startingAmount()} and the goal's
 * contributions. A stored copy would need something to keep it true when a contribution is
 * corrected or a withdrawal recorded, and nothing would.
 *
 * <p><b>The currency is fixed at creation.</b> A goal with contributions in pounds cannot become
 * one in euros without every figure being reinterpreted, so there is no setter for it.
 *
 * <p>The linked account is <b>informational</b> (decision 1). It says where the money is kept, and
 * it is never the source of progress: one account often holds several goals.
 */
@Entity
@Table(name = "goals")
public class Goal implements HouseholdOwned {

    /** 1–80 characters after trimming, matching {@code ck_goals_name_length}. */
    public static final int NAME_MAX_LENGTH = 80;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Column(name = "target_amount", nullable = false)
    private BigDecimal targetAmount;

    /** The first of the target month, or null for a goal with no date. */
    @Column(name = "target_month")
    private LocalDate targetMonth;

    @Column(name = "starting_amount", nullable = false)
    private BigDecimal startingAmount;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "archived", nullable = false)
    private boolean archived;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** For Hibernate and the factory. Does nothing and cannot throw — see {@code Category}. */
    protected Goal() {
        // Hibernate, and of().
    }

    /**
     * @param household the owning household, required: a {@code UUID} parameter here would accept
     *     an id a caller supplied, which is never evidence of ownership
     * @param target positive, and in the currency the goal will be denominated in for good
     */
    public static Goal of(Household household, String name, Money target) {
        Objects.requireNonNull(household, "household");
        Objects.requireNonNull(target, "target");
        Goal goal = new Goal();
        goal.householdId = household.id();
        goal.currency = target.currency().getCurrencyCode();
        goal.rename(name);
        goal.retarget(target);
        goal.startingAmount = Money.zero(target.currency()).amount();
        return goal;
    }

    public UUID id() {
        return id;
    }

    @Override
    public UUID householdId() {
        return householdId;
    }

    public String name() {
        return name;
    }

    public Currency currency() {
        return Currency.getInstance(currency);
    }

    public Money targetAmount() {
        return Money.of(targetAmount, currency());
    }

    public Optional<YearMonth> targetMonth() {
        return Optional.ofNullable(targetMonth).map(YearMonth::from);
    }

    public Money startingAmount() {
        return Money.of(startingAmount, currency());
    }

    public Optional<UUID> accountId() {
        return Optional.ofNullable(accountId);
    }

    public boolean isArchived() {
        return archived;
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

    public void rename(String newName) {
        String trimmed = Objects.requireNonNull(newName, "name").strip();
        if (trimmed.isEmpty() || trimmed.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "a goal name must be 1–" + NAME_MAX_LENGTH + " characters");
        }
        this.name = trimmed;
    }

    /**
     * @throws IllegalArgumentException if not positive, or not in the goal's currency. A target of
     *     nothing is not a goal
     */
    public void retarget(Money newTarget) {
        requireOwnCurrency(newTarget);
        if (!newTarget.isPositive()) {
            throw new IllegalArgumentException("a goal's target must be more than zero");
        }
        this.targetAmount = newTarget.amount();
    }

    /** {@code null} removes the month, which is ordinary: an emergency fund has none. */
    public void aimFor(YearMonth month) {
        this.targetMonth = month == null ? null : month.atDay(1);
    }

    /**
     * What was already set aside when tracking started.
     *
     * @throws IllegalArgumentException if negative or in another currency
     */
    public void startFrom(Money alreadySaved) {
        requireOwnCurrency(alreadySaved);
        if (alreadySaved.isNegative()) {
            throw new IllegalArgumentException("a goal's starting amount cannot be negative");
        }
        this.startingAmount = alreadySaved.amount();
    }

    /**
     * Where the money is kept, or {@code null} for nowhere in particular.
     *
     * <p>The caller has already resolved the account through the household and checked its
     * currency: this entity holds an id, not the account, and cannot do either.
     */
    public void keptIn(UUID newAccountId) {
        this.accountId = newAccountId;
    }

    /** Retires the goal: its history still reads, and it leaves the month's plan. Idempotent. */
    public void archive() {
        this.archived = true;
    }

    public void unarchive() {
        this.archived = false;
    }

    /**
     * Marks the goal changed although none of its own columns did.
     *
     * <p>A change to the monthly plan is a change to the goal: it moves the projection and this
     * month's left-over figure, and the plan {@code PUT} is guarded by the goal's {@code version}.
     * Hibernate only bumps {@code @Version} for a dirty entity, so the plan's writer marks it.
     */
    public void planChanged(Instant at) {
        this.updatedAt = Objects.requireNonNull(at, "at");
    }

    private void requireOwnCurrency(Money amount) {
        Objects.requireNonNull(amount, "amount");
        if (!amount.currency().getCurrencyCode().equals(currency)) {
            throw new IllegalArgumentException("a goal's amounts are in its own currency");
        }
    }

    /** Ids only. The name is the most disclosing field this feature has. */
    @Override
    public String toString() {
        return "Goal[id=" + id + ", version=" + version + "]";
    }
}
