package com.budgetowl.income.domain;

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
 * Money arriving on a schedule.
 *
 * <p><b>Income projects no occurrences.</b> Nobody ticks their salary off; they notice it arrived.
 * The period summary counts how many times a source falls in the month and multiplies — which is
 * why a fortnightly wage is worth more in a five-Friday month, and why that is not a rounding error
 * to be smoothed away.
 */
@Entity
@Table(name = "income_sources")
public class IncomeSource implements HouseholdOwned {

    public static final int NAME_MAX_LENGTH = 60;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

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

    @Column(name = "account_id")
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_of_truth", nullable = false)
    private SourceOfTruth sourceOfTruth = SourceOfTruth.MANUAL_ESTIMATE;

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
    protected IncomeSource() {
        // Hibernate, and of().
    }

    public static IncomeSource of(
            Household household,
            String name,
            Money amount,
            Recurrence recurrence,
            Currency baseCurrency) {
        Objects.requireNonNull(household, "household");
        IncomeSource source = new IncomeSource();
        source.householdId = household.id();
        source.rename(name);
        source.expect(amount, baseCurrency);
        source.rescheduleTo(recurrence);
        return source;
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

    public BigDecimal amount() {
        return amount;
    }

    public Recurrence recurrence() {
        return new Recurrence(
                schedule,
                dayOfMonth,
                scheduleMonth,
                dayOfWeek == null ? null : DayOfWeek.of(dayOfWeek),
                anchorDate);
    }

    /** Which account it lands in. Optional: a household may track income it cannot attribute. */
    public Optional<UUID> accountId() {
        return Optional.ofNullable(accountId);
    }

    public SourceOfTruth sourceOfTruth() {
        return sourceOfTruth;
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
                    "an income source name must be 1–" + NAME_MAX_LENGTH + " characters");
        }
        this.name = trimmed;
    }

    public void expect(Money newAmount, Currency baseCurrency) {
        Objects.requireNonNull(newAmount, "amount");
        if (!newAmount.currency().equals(baseCurrency)) {
            throw new IllegalArgumentException(
                    "income is denominated in the household's base currency");
        }
        if (newAmount.isNegative()) {
            throw new IllegalArgumentException("an income amount cannot be negative");
        }
        this.amount = newAmount.amount();
    }

    public void rescheduleTo(Recurrence recurrence) {
        Objects.requireNonNull(recurrence, "recurrence");
        this.schedule = recurrence.kind();
        this.dayOfMonth = recurrence.dayOfMonth();
        this.scheduleMonth = recurrence.month();
        this.dayOfWeek = recurrence.dayOfWeek() == null ? null : recurrence.dayOfWeek().getValue();
        this.anchorDate = recurrence.anchorDate();
    }

    public void landsIn(UUID newAccountId) {
        this.accountId = newAccountId;
    }

    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }
}
