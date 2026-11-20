package com.budgetowl.envelope.domain;

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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * An amount you decided on, that you spend down.
 *
 * <p><b>There is no {@code remaining} here, and that is the feature.</b> It is {@code amount} minus
 * the sum of the entries, computed when somebody asks — because the only moment the figure is worth
 * anything is while they are still standing in the shop, and a stored copy is wrong the instant an
 * entry lands.
 *
 * <p><b>The period is a LABEL</b> (ADR-0031). {@code 2026-08-01} means "the envelope for August
 * 2026"; it is not a lower bound on entry dates. An entry belongs to an envelope because somebody
 * logged it there. That is what lets slice 9 make a month start on the 25th without rewriting a
 * single row.
 *
 * <p><b>There is no {@code rollover}</b>, also ADR-0031. The designs show the affordance and say
 * nothing about an overspent envelope, which is the half that matters. A column that is always
 * false reads as a feature and is not one.
 *
 * <p>Like {@link com.budgetowl.transaction.domain.Transaction}, this entity has no currency: an
 * envelope is denominated in its household's base currency, and a second copy of that fact would
 * eventually disagree with the first.
 */
@Entity
@Table(name = "envelopes")
public class Envelope implements HouseholdOwned {

    /** 1–60 characters after trimming, matching {@code ck_envelopes_name_length}. */
    public static final int NAME_MAX_LENGTH = 60;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "name", nullable = false)
    private String name;

    /** Optional: "Adhoc" is a perfectly good envelope with no category behind it. */
    @Column(name = "category_id")
    private UUID categoryId;

    /**
     * Fixed at creation. Moving an envelope between months would move its entries with it and
     * change what two periods' totals mean; the user creates the next month's envelope instead.
     */
    @Column(name = "period", nullable = false, updatable = false)
    private LocalDate period;

    /** {@code NUMERIC(19,4)}, never negative — you cannot set aside minus forty pounds. */
    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

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

    /**
     * For Hibernate, and for the factory below. It does nothing and cannot throw — see {@code
     * Category} for why a validating constructor is a SpotBugs finding.
     */
    protected Envelope() {
        // Hibernate, and of().
    }

    /**
     * @param household the owning household, required — a {@code UUID} parameter here would accept
     *     an id a caller supplied, which is never evidence of ownership
     * @param period the month this envelope is for; only its year and month are kept
     * @param amount in the household's base currency, and rejected if it is not
     */
    public static Envelope of(Household household, String name, YearMonth period, Money amount) {
        Objects.requireNonNull(household, "household");
        Envelope envelope = new Envelope();
        envelope.householdId = household.id();
        envelope.period = Objects.requireNonNull(period, "period").atDay(1);
        envelope.rename(name);
        envelope.setAmount(amount, household.baseCurrency());
        return envelope;
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

    public Optional<UUID> categoryId() {
        return Optional.ofNullable(categoryId);
    }

    /** The month this envelope is for. */
    public YearMonth period() {
        return YearMonth.from(period);
    }

    /** What was decided on, with no currency attached — see the class Javadoc. */
    public BigDecimal amount() {
        return amount;
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
                    "an envelope name must be 1–" + NAME_MAX_LENGTH + " characters");
        }
        this.name = trimmed;
    }

    /** {@code null} removes the category, which is ordinary — not every envelope has one. */
    public void fileUnder(UUID newCategoryId) {
        this.categoryId = newCategoryId;
    }

    /**
     * @throws IllegalArgumentException if negative, or not in the household's base currency. Zero
     *     is allowed: deciding to spend nothing on something is a real decision.
     */
    public void setAmount(Money newAmount, java.util.Currency baseCurrency) {
        Objects.requireNonNull(newAmount, "amount");
        if (!newAmount.currency().equals(baseCurrency)) {
            throw new IllegalArgumentException(
                    "an envelope is denominated in the household's base currency");
        }
        if (newAmount.isNegative()) {
            throw new IllegalArgumentException("an envelope amount cannot be negative");
        }
        this.amount = newAmount.amount();
    }

    /** Keeps the log readable, hides the envelope from the period's list. Idempotent. */
    public void archive() {
        this.archived = true;
    }

    public void unarchive() {
        this.archived = false;
    }
}
