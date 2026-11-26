package com.budgetowl.checkin.domain;

import com.budgetowl.common.HouseholdOwned;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One answer to one derived item of a check-in.
 *
 * <p><b>The item is not stored; its key is derived from the references</b>, so the key cannot
 * disagree with them: {@code cat.<transactionId>}, {@code bill.<billId>.<date>}, {@code alloc}.
 *
 * <p><b>Nothing here takes effect until the check-in is completed.</b> An answer is a statement of
 * intent held against the {@link #subjectVersion() version} of the row it was given about, and
 * completion refuses it if that row has moved (decision 9).
 *
 * <p>{@link #record} enforces the per-kind shape that {@code ck_check_in_answers_*} enforces in the
 * database. The service is where the message naming the field comes from; this, and then the
 * constraint, are the controls.
 */
@Entity
@Table(name = "check_in_answers")
public class CheckInAnswer implements HouseholdOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "check_in_id", nullable = false, updatable = false)
    private UUID checkInId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_kind", nullable = false, updatable = false)
    private ItemKind itemKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "choice", nullable = false)
    private AnswerChoice choice;

    @Column(name = "transaction_id", updatable = false)
    private UUID transactionId;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "bill_id")
    private UUID billId;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "goal_id")
    private UUID goalId;

    @Column(name = "debt_id")
    private UUID debtId;

    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "remember", nullable = false)
    private boolean remember;

    @Column(name = "record_debt_payment", nullable = false)
    private boolean recordDebtPayment;

    @Column(name = "by_rule", nullable = false)
    private boolean byRule;

    @Column(name = "subject_version")
    private Long subjectVersion;

    @Column(name = "answered_by")
    private UUID answeredBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** For Hibernate and the factories. Does nothing and cannot throw. */
    protected CheckInAnswer() {
        // Hibernate.
    }

    /** An answer about a transaction: {@code odd.}, {@code cat.} or {@code chg.}. */
    public static CheckInAnswer aboutTransaction(
            CheckIn checkIn, ItemKind kind, UUID transactionId) {
        if (kind == ItemKind.OVERDUE_BILL || kind == ItemKind.ALLOCATION) {
            throw new IllegalArgumentException("that kind is not about a transaction");
        }
        CheckInAnswer answer = blank(checkIn, kind);
        answer.transactionId = Objects.requireNonNull(transactionId, "transactionId");
        return answer;
    }

    /** An answer about an overdue occurrence: {@code bill.<billId>.<date>}. */
    public static CheckInAnswer aboutOccurrence(CheckIn checkIn, UUID billId, LocalDate dueDate) {
        CheckInAnswer answer = blank(checkIn, ItemKind.OVERDUE_BILL);
        answer.billId = Objects.requireNonNull(billId, "billId");
        answer.dueDate = Objects.requireNonNull(dueDate, "dueDate");
        return answer;
    }

    /** The step 4 answer: {@code alloc}. */
    public static CheckInAnswer aboutAllocation(CheckIn checkIn) {
        return blank(checkIn, ItemKind.ALLOCATION);
    }

    private static CheckInAnswer blank(CheckIn checkIn, ItemKind kind) {
        Objects.requireNonNull(checkIn, "checkIn");
        CheckInAnswer answer = new CheckInAnswer();
        answer.householdId = checkIn.householdId();
        answer.checkInId = checkIn.id();
        answer.itemKind = Objects.requireNonNull(kind, "kind");
        return answer;
    }

    /**
     * Records the answer, replacing whatever this row said before.
     *
     * @param shape the references the choice carries. Anything the kind does not accept is refused,
     *     not ignored: a field quietly dropped is how a client comes to believe it set something
     * @throws IllegalArgumentException for a choice or shape the kind does not take
     */
    public void record(AnswerChoice newChoice, AnswerShape shape, Long newSubjectVersion, UUID by) {
        Objects.requireNonNull(newChoice, "choice");
        Objects.requireNonNull(shape, "shape");
        if (!itemKind.accepts(newChoice)) {
            throw new IllegalArgumentException("that choice does not answer this kind of item");
        }
        shape.requireFits(itemKind, newChoice);
        this.choice = newChoice;
        this.categoryId = shape.categoryId();
        if (itemKind != ItemKind.OVERDUE_BILL) {
            // An overdue item's bill and date are its SUBJECT, fixed at creation. Everywhere else
            // they are the answer's TARGET, and move with it.
            this.billId = shape.billId();
            this.dueDate = shape.dueDate();
        }
        this.goalId = shape.goalId();
        this.debtId = shape.debtId();
        this.amount = shape.amount();
        this.remember = shape.remember();
        this.recordDebtPayment = shape.recordDebtPayment();
        this.subjectVersion = newSubjectVersion;
        this.answeredBy = by;
    }

    /** Marks this as a rule's pre-answer, written at completion so the summary can count it. */
    public void suppliedByRule() {
        this.byRule = true;
    }

    /** The item this answers, in the form the API issues. */
    public String itemKey() {
        return switch (itemKind) {
            case OVERDUE_BILL -> ItemKeys.occurrence(billId, dueDate);
            case UNUSUAL_CHARGE -> ItemKeys.unusual(transactionId);
            case CATEGORIZE -> ItemKeys.categorize(transactionId);
            case BILL_CHARGE -> ItemKeys.charge(transactionId);
            case ALLOCATION -> ItemKeys.ALLOCATION;
        };
    }

    public UUID id() {
        return id;
    }

    @Override
    public UUID householdId() {
        return householdId;
    }

    public UUID checkInId() {
        return checkInId;
    }

    public ItemKind itemKind() {
        return itemKind;
    }

    public AnswerChoice choice() {
        return choice;
    }

    public Optional<UUID> transactionId() {
        return Optional.ofNullable(transactionId);
    }

    public Optional<UUID> categoryId() {
        return Optional.ofNullable(categoryId);
    }

    public Optional<UUID> billId() {
        return Optional.ofNullable(billId);
    }

    public Optional<LocalDate> dueDate() {
        return Optional.ofNullable(dueDate);
    }

    public Optional<UUID> goalId() {
        return Optional.ofNullable(goalId);
    }

    public Optional<UUID> debtId() {
        return Optional.ofNullable(debtId);
    }

    public Optional<BigDecimal> amount() {
        return Optional.ofNullable(amount);
    }

    public boolean remember() {
        return remember;
    }

    public boolean recordDebtPayment() {
        return recordDebtPayment;
    }

    public boolean isByRule() {
        return byRule;
    }

    public Optional<Long> subjectVersion() {
        return Optional.ofNullable(subjectVersion);
    }

    public Optional<UUID> answeredBy() {
        return Optional.ofNullable(answeredBy);
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

    /** Ids and kinds only: never an amount, never a category name. */
    @Override
    public String toString() {
        return "CheckInAnswer[id=" + id + ", kind=" + itemKind + ", choice=" + choice + "]";
    }
}
