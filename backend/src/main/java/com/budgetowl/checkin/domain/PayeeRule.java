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
 * {@code payee → category}: "always file this payee here".
 *
 * <p><b>Created only by completing a check-in, and it only ever pre-answers a later one</b>
 * (ADR-0041). Import and manual entry never read it. One per payee ({@code
 * uq_payee_rules_household_payee}); a different answer replaces it.
 */
@Entity
@Table(name = "payee_rules")
public class PayeeRule implements HouseholdOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "payee_id", nullable = false, updatable = false)
    private UUID payeeId;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

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
    protected PayeeRule() {
        // Hibernate.
    }

    /** The ids have already been resolved through the household by the caller. */
    public static PayeeRule of(UUID householdId, UUID payeeId, UUID categoryId, UUID createdBy) {
        PayeeRule rule = new PayeeRule();
        rule.householdId = Objects.requireNonNull(householdId, "householdId");
        rule.payeeId = Objects.requireNonNull(payeeId, "payeeId");
        rule.fileUnder(categoryId, createdBy);
        return rule;
    }

    /** Replaces where the payee files, as a later check-in's different answer does. */
    public void fileUnder(UUID newCategoryId, UUID by) {
        this.categoryId = Objects.requireNonNull(newCategoryId, "categoryId");
        this.createdBy = by;
    }

    public UUID id() {
        return id;
    }

    @Override
    public UUID householdId() {
        return householdId;
    }

    public UUID payeeId() {
        return payeeId;
    }

    public UUID categoryId() {
        return categoryId;
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

    /** Ids only: a rule is a standing map of where a household shops and how it classifies it. */
    @Override
    public String toString() {
        return "PayeeRule[id=" + id + "]";
    }
}
