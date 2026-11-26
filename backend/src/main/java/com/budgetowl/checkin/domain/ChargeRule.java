package com.budgetowl.checkin.domain;

import com.budgetowl.common.HouseholdOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * {@code (payee, account) → bill}: "this charge pays that bill".
 *
 * <p><b>Created only by completing a check-in, and it only ever pre-answers a later one</b>
 * (ADR-0041). Nothing matches at import or sync; that is slice 16's. One per {@code (payee,
 * account)}; a different mapping replaces it.
 *
 * <p>{@code standing} and {@code matchKind} from the domain model are deliberately absent: every
 * rule is standing, and the match kind is the bill's own {@code amountVaries} and {@code tolerance}
 * (docs/features/weekly-check-in.md, Step 3).
 */
@Entity
@Table(name = "charge_rules")
public class ChargeRule implements HouseholdOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "bill_id", nullable = false)
    private UUID billId;

    @Column(name = "payee_id", nullable = false, updatable = false)
    private UUID payeeId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "created_by")
    private UUID createdBy;

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
    protected ChargeRule() {
        // Hibernate.
    }

    /** The ids have already been resolved through the household by the caller. */
    public static ChargeRule of(
            UUID householdId, UUID payeeId, UUID accountId, UUID billId, UUID createdBy) {
        ChargeRule rule = new ChargeRule();
        rule.householdId = Objects.requireNonNull(householdId, "householdId");
        rule.payeeId = Objects.requireNonNull(payeeId, "payeeId");
        rule.accountId = Objects.requireNonNull(accountId, "accountId");
        rule.mapTo(billId, createdBy);
        return rule;
    }

    public void mapTo(UUID newBillId, UUID by) {
        this.billId = Objects.requireNonNull(newBillId, "billId");
        this.createdBy = by;
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

    public UUID payeeId() {
        return payeeId;
    }

    public UUID accountId() {
        return accountId;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }

    /** Ids only: which payee settles which named bill is sensitive. */
    @Override
    public String toString() {
        return "ChargeRule[id=" + id + "]";
    }
}
