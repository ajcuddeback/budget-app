package com.budgetowl.goal.domain;

import com.budgetowl.common.HouseholdOwned;
import com.budgetowl.common.Money;
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
 * Money a household recorded setting aside for a goal, or taking out of it.
 *
 * <p><b>A plan record, not a transaction</b>, for the reasons ADR-0035 gives for debt payments:
 * recording one writes no ledger row and moves no account balance, so the real transfer into
 * savings that a file import later brings in is not counted twice. {@link #transactionId()} is how
 * a household attaches that real movement, and it is evidence, never the source of the figure.
 *
 * <p><b>Signed and never zero</b> (decision 6). A negative contribution is a withdrawal: an
 * emergency fund exists to be spent.
 *
 * <p><b>The period is a label</b> (ADR-0031), set from the calendar month of the date. Stored
 * rather than derived on read, so a configurable month start later changes where new contributions
 * land and rewrites nothing.
 */
@Entity
@Table(name = "goal_contributions")
public class GoalContribution implements HouseholdOwned {

    /** 1–500 characters after trimming, matching {@code ck_goal_contributions_note}. */
    public static final int NOTE_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    /**
     * Fixed at creation. Moving a contribution between goals changes two saved figures at once; the
     * household removes it and records it where it meant.
     */
    @Column(name = "goal_id", nullable = false, updatable = false)
    private UUID goalId;

    @Column(name = "contributed_on", nullable = false)
    private LocalDate contributedOn;

    @Column(name = "period", nullable = false)
    private LocalDate period;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "note")
    private String note;

    @Column(name = "transaction_id")
    private UUID transactionId;

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
    protected GoalContribution() {
        // Hibernate, and to().
    }

    /**
     * @param goal the goal it is recorded against, required: a {@code UUID} parameter would accept
     *     an id a caller supplied, which is never evidence of ownership
     * @param amount signed and non-zero, in the goal's currency
     */
    public static GoalContribution to(Goal goal, LocalDate on, Money amount) {
        Objects.requireNonNull(goal, "goal");
        GoalContribution contribution = new GoalContribution();
        contribution.householdId = goal.householdId();
        contribution.goalId = goal.id();
        contribution.on(on);
        contribution.correctAmount(amount, goal);
        return contribution;
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

    public LocalDate contributedOn() {
        return contributedOn;
    }

    /** The month this counts towards. */
    public YearMonth period() {
        return YearMonth.from(period);
    }

    /** Signed: negative is a withdrawal. No currency attached — it is the goal's. */
    public BigDecimal amount() {
        return amount;
    }

    public Optional<String> note() {
        return Optional.ofNullable(note);
    }

    public Optional<UUID> transactionId() {
        return Optional.ofNullable(transactionId);
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

    /** Moves the date, and with it the month the contribution counts towards. */
    public void on(LocalDate newDate) {
        this.contributedOn = Objects.requireNonNull(newDate, "date");
        this.period = YearMonth.from(newDate).atDay(1);
    }

    /**
     * @throws IllegalArgumentException if zero, or not in the goal's currency. A contribution of
     *     nothing records nothing
     */
    public void correctAmount(Money corrected, Goal goal) {
        Objects.requireNonNull(corrected, "amount");
        Objects.requireNonNull(goal, "goal");
        if (!corrected.currency().equals(goal.currency())) {
            throw new IllegalArgumentException("a contribution is in its goal's currency");
        }
        if (corrected.isZero()) {
            throw new IllegalArgumentException("a contribution of zero records nothing");
        }
        this.amount = corrected.amount();
    }

    /** {@code null} or blank clears the note; anything else is stored trimmed. */
    public void annotate(String newNote) {
        if (newNote == null || newNote.isBlank()) {
            this.note = null;
            return;
        }
        String trimmed = newNote.strip();
        if (trimmed.length() > NOTE_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "a note must be at most " + NOTE_MAX_LENGTH + " characters");
        }
        this.note = trimmed;
    }

    /**
     * The real movement this records, or {@code null} to detach it.
     *
     * <p>The caller has already resolved it through the household; this holds an id only.
     */
    public void evidencedBy(UUID newTransactionId) {
        this.transactionId = newTransactionId;
    }

    /** Ids only: the amount is a household's disposable income, and the note may hold anything. */
    @Override
    public String toString() {
        return "GoalContribution[id=" + id + ", goalId=" + goalId + "]";
    }
}
