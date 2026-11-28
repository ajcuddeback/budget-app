package com.budgetowl.bill.domain;

import com.budgetowl.common.HouseholdOwned;
import com.budgetowl.common.Money;
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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * What a household recorded about one bill in one period.
 *
 * <p><b>A row exists only once somebody has recorded something</b> (ADR-0032). A period nobody has
 * touched has no rows and reads exactly like one where every occurrence is still {@code DUE} — that
 * equivalence is what makes a scheduler unnecessary, and what keeps an instance correct after a
 * month switched off.
 *
 * <p>Its real identity is {@code (billId, period)}, enforced by {@code
 * ux_bill_occurrences_bill_period}. The surrogate id exists for Hibernate; the API addresses the
 * natural key, because that URL is valid before anything exists at it.
 *
 * <p><b>The due date and expected amount are copied in, not read back from the bill.</b> A period
 * somebody settled keeps the figures it was settled against: editing a bill is not retrospective
 * for a month that is already answered.
 */
@Entity
@Table(name = "bill_occurrences")
public class BillOccurrence implements HouseholdOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "bill_id", nullable = false, updatable = false)
    private UUID billId;

    @Column(name = "period", nullable = false, updatable = false)
    private LocalDate period;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "expected_amount", nullable = false)
    private BigDecimal expectedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OccurrenceStatus status;

    @Column(name = "actual_amount")
    private BigDecimal actualAmount;

    @Column(name = "paid_date")
    private LocalDate paidDate;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_source", nullable = false)
    private StatusSource statusSource = StatusSource.MANUAL;

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
    protected BillOccurrence() {
        // Hibernate, and recordedFor().
    }

    /**
     * A row for a period somebody is about to record something against.
     *
     * @param dueDate and {@code expectedAmount}: what the projection said at this moment. Copied in
     *     deliberately — see the class Javadoc
     */
    public static BillOccurrence recordedFor(
            Bill bill, YearMonth period, LocalDate dueDate, Money expectedAmount) {
        Objects.requireNonNull(bill, "bill");
        BillOccurrence occurrence = new BillOccurrence();
        occurrence.householdId = bill.householdId();
        occurrence.billId = bill.id();
        occurrence.period = Objects.requireNonNull(period, "period").atDay(1);
        occurrence.dueDate = Objects.requireNonNull(dueDate, "dueDate");
        occurrence.expectedAmount =
                Objects.requireNonNull(expectedAmount, "expectedAmount").amount();
        occurrence.status = OccurrenceStatus.DUE;
        return occurrence;
    }

    public UUID id() {
        return id;
    }

    @Override
    public UUID householdId() {
        return householdId;
    }

    public UUID billId() {
        return billId;
    }

    public YearMonth period() {
        return YearMonth.from(period);
    }

    public LocalDate dueDate() {
        return dueDate;
    }

    public BigDecimal expectedAmount() {
        return expectedAmount;
    }

    public OccurrenceStatus status() {
        return status;
    }

    public Optional<BigDecimal> actualAmount() {
        return Optional.ofNullable(actualAmount);
    }

    public Optional<LocalDate> paidDate() {
        return Optional.ofNullable(paidDate);
    }

    public Optional<UUID> transactionId() {
        return Optional.ofNullable(transactionId);
    }

    public StatusSource statusSource() {
        return statusSource;
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
     * Whether this is past its due date and still unsettled.
     *
     * <p><b>Derived, never stored.</b> Takes today as a parameter rather than reading a clock, so
     * that it is a pure function and the service decides what "today" means once.
     */
    public boolean isOverdue(LocalDate today) {
        return status == OccurrenceStatus.DUE && dueDate.isBefore(today);
    }

    /**
     * Records that it was paid.
     *
     * @param actual what was really paid — may differ from what was budgeted, and the difference is
     *     the useful number (ADR-0025)
     * @param transactionId the transaction that settled it, or null. Nothing sets this yet: by hand
     *     is the primary path, and matching needs a connected account (slice 16)
     */
    public void markPaid(
            Money actual,
            LocalDate paidOn,
            UUID transactionId,
            StatusSource source,
            Currency baseCurrency) {
        Objects.requireNonNull(actual, "actualAmount");
        if (!actual.currency().equals(baseCurrency)) {
            throw new IllegalArgumentException(
                    "a bill is denominated in the household's base currency");
        }
        if (actual.isNegative()) {
            throw new IllegalArgumentException("a paid amount cannot be negative");
        }
        this.status = OccurrenceStatus.PAID;
        this.actualAmount = actual.amount();
        this.paidDate = Objects.requireNonNull(paidOn, "paidDate");
        this.transactionId = transactionId;
        this.statusSource = Objects.requireNonNull(source, "statusSource");
    }

    /** It did not apply this period. Not the same as unpaid. */
    public void markSkipped() {
        clearPaidFields();
        this.status = OccurrenceStatus.SKIPPED;
    }

    /** Back to unsettled — what the projection would have said. */
    public void markDue() {
        clearPaidFields();
        this.status = OccurrenceStatus.DUE;
    }

    /**
     * The paid fields belong to a PAID occurrence and to no other.
     *
     * <p>{@code ck_bill_occurrences_paid_fields} says the same thing. A DUE row carrying an actual
     * amount would make "what did we really pay this month" quietly wrong.
     */
    private void clearPaidFields() {
        this.actualAmount = null;
        this.paidDate = null;
        this.transactionId = null;
        this.statusSource = StatusSource.MANUAL;
    }
}
