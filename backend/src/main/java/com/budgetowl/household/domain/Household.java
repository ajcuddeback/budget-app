package com.budgetowl.household.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * The ownership root for all financial data (ADR-0017). An instance holds exactly one, and its
 * {@code OWNER} is the operator (ADR-0026) — {@code uq_households_singleton} makes a second one
 * impossible rather than merely unsupported.
 *
 * <p><b>A household always has at least one {@code OWNER}</b>, and that is enforced by the
 * database: {@code households.owner_count} is maintained by a trigger and checked by a deferred
 * constraint trigger at {@code COMMIT}. Two owners removing each other at the same instant
 * therefore cannot both succeed. The counter is deliberately <em>not</em> mapped here — it is the
 * database's, the application never writes it, and a mapped copy would go stale the moment a
 * membership changed under it.
 */
@Entity
@Table(name = "households")
public class Household {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "base_currency", nullable = false, length = 3)
    private String baseCurrency;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Household() {
        // for JPA
    }

    public static Household named(String name, Currency baseCurrency) {
        Household household = new Household();
        household.name = Objects.requireNonNull(name, "name");
        household.baseCurrency =
                Objects.requireNonNull(baseCurrency, "baseCurrency").getCurrencyCode();
        return household;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Currency baseCurrency() {
        return Currency.getInstance(baseCurrency);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public void rename(String newName) {
        this.name = Objects.requireNonNull(newName, "name");
    }

    /**
     * Changes the currency amounts are reported in. It never rewrites stored amounts — those stay
     * in their account's own currency and are converted only for display (ADR-0022).
     */
    public void changeBaseCurrency(Currency newBaseCurrency) {
        this.baseCurrency =
                Objects.requireNonNull(newBaseCurrency, "baseCurrency").getCurrencyCode();
    }

    @Override
    public boolean equals(Object other) {
        // Reflexive even before the row exists: an unsaved entity must still equal
        // itself, or putting one in a Set loses it.
        return this == other
                || (other instanceof Household that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return Household.class.hashCode();
    }

    @Override
    public String toString() {
        return "Household[id=" + id + "]";
    }
}
