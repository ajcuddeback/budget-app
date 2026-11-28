package com.budgetowl.bill.domain;

import com.budgetowl.common.HouseholdOwned;
import com.budgetowl.common.Money;
import com.budgetowl.common.schedule.Recurrence;
import com.budgetowl.common.schedule.ScheduleKind;
import com.budgetowl.household.domain.Household;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * A named obligation that recurs.
 *
 * <p><b>A plan, never a transaction</b> (ADR-0025). Nothing here writes a ledger row: marking a
 * bill paid records what the household says happened, and only a real transaction is ever an
 * expense. This app is not the authority on whether money moved.
 *
 * <p><b>It stores no occurrences either</b> (ADR-0032). {@link #recurrence()} projects them on
 * demand, so there is no job, no clock in the write path, and nothing that decays while an instance
 * is switched off. Editing the amount or the due day instantly corrects every future period,
 * because nothing was written down to contradict it.
 */
@Entity
@Table(name = "bills")
public class Bill implements HouseholdOwned {

    public static final int NAME_MAX_LENGTH = 60;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "expected_amount", nullable = false)
    private BigDecimal expectedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule", nullable = false)
    private ScheduleKind schedule;

    /**
     * {@code smallint} in the database, {@code Integer} here.
     *
     * <p>The JDBC type is spelled out because Hibernate's schema validation compares them and
     * refuses a mismatch at start-up — which is the right behaviour and caught this. A day of the
     * month does not need four bytes, and the alternative (a {@code Short} field) would push the
     * narrower type through {@code Recurrence} and every caller for no gain.
     */
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "day_of_month")
    private Integer dayOfMonth;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "schedule_month")
    private Integer scheduleMonth;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "day_of_week")
    private Integer dayOfWeek;

    @Column(name = "anchor_date")
    private LocalDate anchorDate;

    @Column(name = "amount_varies", nullable = false)
    private boolean amountVaries;

    @Column(name = "tolerance")
    private BigDecimal tolerance;

    @Column(name = "active", nullable = false)
    private boolean active = true;

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
    protected Bill() {
        // Hibernate, and of().
    }

    public static Bill of(
            Household household,
            String name,
            Money expectedAmount,
            Recurrence recurrence,
            Currency baseCurrency) {
        Objects.requireNonNull(household, "household");
        Bill bill = new Bill();
        bill.householdId = household.id();
        bill.rename(name);
        bill.expect(expectedAmount, baseCurrency);
        bill.rescheduleTo(recurrence);
        return bill;
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

    /**
     * What is budgeted, with no currency attached — the household's base currency denominates it.
     */
    public BigDecimal expectedAmount() {
        return expectedAmount;
    }

    /** The schedule, as the value object that knows how to project it. */
    public Recurrence recurrence() {
        return new Recurrence(
                schedule,
                dayOfMonth,
                scheduleMonth,
                dayOfWeek == null ? null : DayOfWeek.of(dayOfWeek),
                anchorDate);
    }

    public boolean amountVaries() {
        return amountVaries;
    }

    /** How far from the expected amount is unremarkable. Present only when the amount varies. */
    public Optional<BigDecimal> tolerance() {
        return Optional.ofNullable(tolerance);
    }

    public boolean isActive() {
        return active;
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
                    "a bill name must be 1–" + NAME_MAX_LENGTH + " characters");
        }
        this.name = trimmed;
    }

    public void fileUnder(UUID newCategoryId) {
        this.categoryId = newCategoryId;
    }

    /**
     * @throws IllegalArgumentException if negative, or not in the household's base currency. Zero
     *     is allowed: a bill that is currently nothing is still a bill you want listed
     */
    public void expect(Money amount, Currency baseCurrency) {
        Objects.requireNonNull(amount, "expectedAmount");
        if (!amount.currency().equals(baseCurrency)) {
            throw new IllegalArgumentException(
                    "a bill is denominated in the household's base currency");
        }
        if (amount.isNegative()) {
            throw new IllegalArgumentException("a bill amount cannot be negative");
        }
        this.expectedAmount = amount.amount();
    }

    /** Replaces the schedule wholesale: the parameters only make sense together. */
    public void rescheduleTo(Recurrence recurrence) {
        Objects.requireNonNull(recurrence, "recurrence");
        this.schedule = recurrence.kind();
        this.dayOfMonth = recurrence.dayOfMonth();
        this.scheduleMonth = recurrence.month();
        this.dayOfWeek = recurrence.dayOfWeek() == null ? null : recurrence.dayOfWeek().getValue();
        this.anchorDate = recurrence.anchorDate();
    }

    /**
     * @param tolerance required when the amount varies, and refused when it does not — a tolerance
     *     on a fixed bill is a number nothing would ever read
     */
    public void variesBy(boolean varies, BigDecimal newTolerance) {
        if (varies) {
            if (newTolerance == null || newTolerance.signum() < 0) {
                throw new IllegalArgumentException(
                        "a bill whose amount varies needs a tolerance of zero or more");
            }
        } else if (newTolerance != null) {
            throw new IllegalArgumentException("a bill with a fixed amount has no tolerance");
        }
        this.amountVaries = varies;
        this.tolerance = newTolerance;
    }

    /** Retires the bill: it stops projecting occurrences and leaves the ones already recorded. */
    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }
}
