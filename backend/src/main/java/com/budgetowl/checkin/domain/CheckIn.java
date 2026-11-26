package com.budgetowl.checkin.domain;

import com.budgetowl.common.HouseholdOwned;
import com.budgetowl.common.Money;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A household's check-in for one ISO week (docs/features/weekly-check-in.md).
 *
 * <p><b>A draft stores almost nothing</b>: who started it and the window it looks at. Every
 * question is derived from the ledger on each read, and only the answers are stored, in {@link
 * CheckInAnswer}. <b>The snapshot is written once, at completion</b>, and the row is read-only from
 * then on.
 *
 * <p><b>The version is the draft's, and every answer moves it.</b> {@link #touch} sets {@code
 * updatedAt} by hand rather than through {@code @UpdateTimestamp}, so that recording an answer —
 * which changes a different row — still dirties this one and bumps {@code @Version}. Completing
 * sends back the version the person last saw, which is how whoever presses Finish is known to have
 * seen every answer.
 */
@Entity
@Table(name = "check_ins")
public class CheckIn implements HouseholdOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "week_start", nullable = false, updatable = false)
    private LocalDate weekStart;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CheckInStatus status;

    @Column(name = "window_start", nullable = false, updatable = false)
    private LocalDate windowStart;

    @Column(name = "started_by", updatable = false)
    private UUID startedBy;

    @Column(name = "completed_by")
    private UUID completedBy;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "currency")
    private String currency;

    @Column(name = "week_target")
    private BigDecimal weekTarget;

    @Column(name = "week_spending")
    private BigDecimal weekSpending;

    @Column(name = "rolled_over")
    private BigDecimal rolledOver;

    @Column(name = "next_week_target")
    private BigDecimal nextWeekTarget;

    @Column(name = "debt_free_month")
    private LocalDate debtFreeMonth;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** For Hibernate and the factory. Does nothing and cannot throw. */
    protected CheckIn() {
        // Hibernate, and start().
    }

    /**
     * A new draft.
     *
     * @param household required: a {@code UUID} would accept one a caller supplied
     * @param windowStart on or before the Monday, and no more than 21 days before it
     */
    public static CheckIn start(
            Household household, IsoWeek week, LocalDate windowStart, UUID startedBy, Instant now) {
        Objects.requireNonNull(household, "household");
        Objects.requireNonNull(week, "week");
        Objects.requireNonNull(windowStart, "windowStart");
        LocalDate monday = week.monday();
        if (windowStart.isAfter(monday) || windowStart.isBefore(monday.minusDays(21))) {
            throw new IllegalArgumentException("a check-in looks back at most 28 days");
        }
        CheckIn checkIn = new CheckIn();
        checkIn.householdId = household.id();
        checkIn.weekStart = monday;
        checkIn.windowStart = windowStart;
        checkIn.startedBy = startedBy;
        checkIn.status = CheckInStatus.DRAFT;
        checkIn.updatedAt = Objects.requireNonNull(now, "now");
        return checkIn;
    }

    public UUID id() {
        return id;
    }

    @Override
    public UUID householdId() {
        return householdId;
    }

    public IsoWeek week() {
        return IsoWeek.startingOn(weekStart);
    }

    public CheckInStatus status() {
        return status;
    }

    public boolean isCompleted() {
        return status == CheckInStatus.COMPLETED;
    }

    public LocalDate windowStart() {
        return windowStart;
    }

    public Optional<UUID> startedBy() {
        return Optional.ofNullable(startedBy);
    }

    public Optional<UUID> completedBy() {
        return Optional.ofNullable(completedBy);
    }

    public Optional<Instant> completedAt() {
        return Optional.ofNullable(completedAt);
    }

    public Optional<Currency> currency() {
        return Optional.ofNullable(currency).map(Currency::getInstance);
    }

    public Optional<BigDecimal> weekTarget() {
        return Optional.ofNullable(weekTarget);
    }

    public Optional<BigDecimal> weekSpending() {
        return Optional.ofNullable(weekSpending);
    }

    public Optional<BigDecimal> rolledOver() {
        return Optional.ofNullable(rolledOver);
    }

    public Optional<BigDecimal> nextWeekTarget() {
        return Optional.ofNullable(nextWeekTarget);
    }

    public Optional<YearMonth> debtFreeMonth() {
        return Optional.ofNullable(debtFreeMonth).map(YearMonth::from);
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

    /**
     * Marks the draft changed, so its version moves although only an answer row was written.
     *
     * @throws IllegalStateException on a completed check-in, which is read-only
     */
    public void touch(Instant now) {
        requireDraft();
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    /**
     * Writes the snapshot and closes the check-in. Called once, at the end of the one transaction
     * that applied every answer.
     *
     * @param weekTarget null when the previous week set none
     * @param rolledOver null unless the surplus was rolled into next week; positive when present
     * @param debtFreeMonth null with no debts or a plan that never clears
     */
    public void complete(
            UUID by,
            Instant at,
            Money weekTarget,
            Money weekSpending,
            Money rolledOver,
            Money nextWeekTarget,
            YearMonth debtFreeMonth) {
        requireDraft();
        Objects.requireNonNull(weekSpending, "weekSpending");
        Objects.requireNonNull(nextWeekTarget, "nextWeekTarget");
        Currency unit = weekSpending.currency();
        for (Money figure : new Money[] {weekTarget, rolledOver, nextWeekTarget}) {
            if (figure != null && !figure.currency().equals(unit)) {
                throw new IllegalArgumentException("a check-in's figures are in one currency");
            }
        }
        if (rolledOver != null && !rolledOver.isPositive()) {
            throw new IllegalArgumentException("only a positive surplus is rolled over");
        }
        this.status = CheckInStatus.COMPLETED;
        this.completedBy = by;
        this.completedAt = Objects.requireNonNull(at, "at");
        this.updatedAt = at;
        this.currency = unit.getCurrencyCode();
        this.weekTarget = weekTarget == null ? null : weekTarget.amount();
        this.weekSpending = weekSpending.amount();
        this.rolledOver = rolledOver == null ? null : rolledOver.amount();
        this.nextWeekTarget = nextWeekTarget.amount();
        this.debtFreeMonth = debtFreeMonth == null ? null : debtFreeMonth.atDay(1);
    }

    private void requireDraft() {
        if (status != CheckInStatus.DRAFT) {
            throw new IllegalStateException("a completed check-in is read-only");
        }
    }

    /** Ids and the week only: the snapshot is a household's weekly disposable income. */
    @Override
    public String toString() {
        return "CheckIn[id=" + id + ", weekStart=" + weekStart + ", status=" + status + "]";
    }
}
