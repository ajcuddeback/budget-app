package com.budgetowl.payee.domain;

import com.budgetowl.common.HouseholdOwned;
import com.budgetowl.household.domain.Household;
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
 * Who money went to, or came from.
 *
 * <p><b>A table rather than a text column on the transaction, from the start.</b> Slice 5 ships no
 * renaming and no merging — the service gets-or-creates a row from the name the user typed — so a
 * plain {@code payee_name text} would have behaved identically today. It would also have made
 * normalisation a migration that extracts names out of every transaction and rewrites them, and
 * normalisation is the point: "STARBUCKS #1234" and "Starbucks" have to be able to reconcile to one
 * payee for reporting and auto-categorisation to be possible at all (docs/domain/model.md).
 *
 * <p>Uniqueness is case-insensitive per household, enforced by {@code ux_payees_household_name}. A
 * person typing "tesco" after "Tesco" gets the row they already have, which is the whole reason
 * get-or-create is safe to do silently.
 */
@Entity
@Table(name = "payees")
public class Payee implements HouseholdOwned {

    /** 1–80 characters after trimming, matching {@code ck_payees_name_length}. */
    public static final int NAME_MAX_LENGTH = 80;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "name", nullable = false)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Payee() {
        // Hibernate.
    }

    public static Payee named(Household household, String name) {
        Payee payee = new Payee();
        payee.householdId = Objects.requireNonNull(household, "household").id();
        payee.rename(name);
        return payee;
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

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    /** 1–80 characters once trimmed; stored trimmed, so raw and trimmed bounds agree. */
    public void rename(String newName) {
        String trimmed = Objects.requireNonNull(newName, "name").strip();
        if (trimmed.isEmpty() || trimmed.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "a payee name must be 1–" + NAME_MAX_LENGTH + " characters");
        }
        this.name = trimmed;
    }
}
