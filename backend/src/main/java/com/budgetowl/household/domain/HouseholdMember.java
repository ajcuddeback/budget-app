package com.budgetowl.household.domain;

import com.budgetowl.auth.domain.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One person's membership of the household, and the row every authorization decision rests on
 * (ADR-0008 as amended by ADR-0017). This is the membership graph, not financial data, which is why
 * it carries a {@code user_id} at all.
 *
 * <p>Removing a membership revokes access and never deletes household financial data — that data
 * belongs to the household, not the person.
 *
 * <p>Demoting or deleting the last {@code OWNER} is refused by the database, not by this class. See
 * {@link Household}.
 */
@Entity
@Table(name = "household_members")
public class HouseholdMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false, updatable = false)
    private Household household;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private UserAccount user;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private HouseholdRole role;

    @CreationTimestamp
    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    /** Null means "use the household's base currency" (ADR-0022) — a meaning, not an unknown. */
    @Column(name = "display_currency", length = 3)
    private String displayCurrency;

    /** Null means "use the platform locale" (ADR-0023). */
    @Column(name = "locale", length = 35)
    private String locale;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected HouseholdMember() {
        // for JPA
    }

    public static HouseholdMember of(Household household, UserAccount user, HouseholdRole role) {
        HouseholdMember member = new HouseholdMember();
        member.household = Objects.requireNonNull(household, "household");
        member.user = Objects.requireNonNull(user, "user");
        member.role = Objects.requireNonNull(role, "role");
        return member;
    }

    public UUID id() {
        return id;
    }

    public Household household() {
        return household;
    }

    public UserAccount user() {
        return user;
    }

    public HouseholdRole role() {
        return role;
    }

    public Instant joinedAt() {
        return joinedAt;
    }

    public Optional<Currency> displayCurrency() {
        return Optional.ofNullable(displayCurrency).map(Currency::getInstance);
    }

    public Optional<Locale> locale() {
        return Optional.ofNullable(locale).map(Locale::forLanguageTag);
    }

    /**
     * Changes this member's role.
     *
     * <p>Who may call this is a service decision — nobody may change their own role, and only an
     * {@code OWNER} may change anyone's. Demoting the last {@code OWNER} fails at {@code COMMIT},
     * from the database, however the call got here.
     */
    public void changeRoleTo(HouseholdRole newRole) {
        this.role = Objects.requireNonNull(newRole, "role");
    }

    /** Null resets the member to the household's base currency. */
    public void preferDisplayCurrency(Currency currency) {
        this.displayCurrency = currency == null ? null : currency.getCurrencyCode();
    }

    /** Null resets the member to the platform locale. */
    public void preferLocale(Locale preferred) {
        this.locale = preferred == null ? null : preferred.toLanguageTag();
    }

    @Override
    public boolean equals(Object other) {
        // Reflexive even before the row exists: an unsaved entity must still equal
        // itself, or putting one in a Set loses it.
        return this == other
                || (other instanceof HouseholdMember that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return HouseholdMember.class.hashCode();
    }

    @Override
    public String toString() {
        return "HouseholdMember[id=" + id + ", role=" + role + "]";
    }
}
