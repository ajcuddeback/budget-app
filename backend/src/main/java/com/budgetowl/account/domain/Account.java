package com.budgetowl.account.domain;

import com.budgetowl.common.CurrencyMismatchException;
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
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A place money sits — a current account, a savings pot, a credit card, cash in a tin, an
 * investment. The first financial entity in the schema, and therefore the first to be {@link
 * HouseholdOwned}.
 *
 * <p><b>Balance is not here.</b> It is derived — this account's opening balance plus the sum of its
 * transactions (docs/domain/model.md) — and deliberately has no accessor on the entity, because a
 * {@code balance()} that returned {@link #openingBalance()} today would keep compiling, keep
 * passing its tests and start lying the moment slice 5 lands. The service that owns the sum owns
 * the method.
 *
 * <p><b>Why a plain {@code householdId} and not a {@code @ManyToOne Household}.</b> {@link
 * com.budgetowl.household.domain.HouseholdMember} maps the association because it is the membership
 * graph and genuinely needs the household object. A financial row needs only the ownership key, and
 * a lazy association would be an N+1 waiting for the first list endpoint that maps it — reading an
 * id off a proxy initialises it when the identifier is field-accessed. The column is the same
 * column, with the same foreign key, and ADR-0017's predicate stays one condition.
 *
 * <p>This class validates itself rather than trusting the edge to have done it
 * (docs/guides/java-style.md). Bean Validation on the request DTO produces the good error message;
 * these checks and the {@code ck_accounts_*} constraints are what remain true when an import path,
 * a CLI or a future slice writes a row without going through a controller.
 */
@Entity
@Table(name = "accounts")
public class Account implements HouseholdOwned {

    /** 1–80 characters after trimming, matching {@code ck_accounts_name_length}. */
    public static final int NAME_MAX_LENGTH = 80;

    /**
     * Exactly four ASCII digits (ADR-0028). Matched with {@link java.util.regex.Matcher#matches()},
     * which anchors both ends implicitly — a Java {@code $} also matches before a trailing newline,
     * so {@code "1234\n"} would slip past the written-out anchors.
     */
    private static final Pattern LAST_FOUR = Pattern.compile("[0-9]{4}");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** The ownership root (ADR-0017). Immutable: an account never moves between households. */
    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "name", nullable = false)
    private String name;

    /** Null means "this account has no number", which is ordinary rather than unknown. */
    @Column(name = "last_four")
    private String lastFour;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private AccountType type;

    /**
     * ISO-4217, stored as its code. Immutable once the account has a transaction — changing it
     * would silently reinterpret every amount already recorded, so that rule is a {@code 409} in
     * the service rather than a no-op here.
     */
    @Column(name = "currency", nullable = false)
    private String currency;

    /** {@code NUMERIC(19,4)}, signed (ADR-0006). Never {@code double}, not even for display. */
    @Column(name = "opening_balance", nullable = false)
    private BigDecimal openingBalance;

    @Column(name = "archived", nullable = false)
    private boolean archived;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic locking. Two people in one household editing one account is a normal Tuesday; the
     * loser gets {@code 409 stale-version} and re-reads, rather than overwriting the winner.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Account() {
        // for JPA
    }

    /**
     * A new account, in the household that will own it.
     *
     * <p>The currency is taken from the opening balance rather than passed separately: the amount
     * and the currency it is denominated in are one fact, and two parameters would let a caller
     * disagree with itself.
     *
     * @param household the owning household, required — a {@code UUID} parameter here would accept
     *     an id from a request body, which is the shape ADR-0017 exists to prevent
     * @param name 1–80 characters once trimmed; stored trimmed
     * @param openingBalance what the account held when the household started tracking it, signed
     */
    public static Account of(
            Household household, String name, AccountType type, Money openingBalance) {
        Account account = new Account();
        // The household must already have been persisted. A null here would reach the database as
        // a NOT NULL violation several frames away from the mistake.
        account.householdId =
                Objects.requireNonNull(
                        Objects.requireNonNull(household, "household").id(), "household.id");
        account.name = requireValidName(name);
        account.type = Objects.requireNonNull(type, "type");
        account.openingBalance = Objects.requireNonNull(openingBalance, "openingBalance").amount();
        account.currency = openingBalance.currency().getCurrencyCode();
        return account;
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

    /** Absent for an account with no number. The client composes {@code name •1234}, not us. */
    public Optional<String> lastFour() {
        return Optional.ofNullable(lastFour);
    }

    public AccountType type() {
        return type;
    }

    public Currency currency() {
        return Currency.getInstance(currency);
    }

    /** What the account held when tracking started, in the account's own currency. Signed. */
    public Money openingBalance() {
        return Money.of(openingBalance, currency());
    }

    public boolean isArchived() {
        return archived;
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
        this.name = requireValidName(newName);
    }

    /** Null clears the digits, which is how an account stops having a number. */
    public void identifyBy(String newLastFour) {
        this.lastFour = requireValidLastFour(newLastFour);
    }

    /** Always permitted. Nothing about stored money depends on the type. */
    public void changeTypeTo(AccountType newType) {
        this.type = Objects.requireNonNull(newType, "type");
    }

    /**
     * Re-denominates the account.
     *
     * <p>The stored figures do not move — 100 becomes 100 of the new currency — which is exactly
     * why this is refused once a transaction exists (a {@code 409} from the service, not a silent
     * no-op). Before the first transaction there is one amount to reinterpret and the user is the
     * person who typed it, so it is free.
     */
    public void changeCurrencyTo(Currency newCurrency) {
        this.currency = Objects.requireNonNull(newCurrency, "currency").getCurrencyCode();
    }

    /**
     * Corrects a mistyped opening balance. Routine bookkeeping: it changes the derived balance and
     * nothing else, and it never rewrites a transaction.
     *
     * @throws CurrencyMismatchException if the amount is not in the account's own currency —
     *     storing it would silently relabel the figure
     */
    public void correctOpeningBalance(Money corrected) {
        Objects.requireNonNull(corrected, "openingBalance");
        if (!corrected.currency().equals(currency())) {
            throw new CurrencyMismatchException(currency(), corrected.currency());
        }
        this.openingBalance = corrected.amount();
    }

    /**
     * Retires the account without losing its history. Idempotent: archiving an archived account is
     * the state the caller asked for, not an error.
     */
    public void archive() {
        this.archived = true;
    }

    /** Brings it back. An archived account's balance was always correct; archiving only hid it. */
    public void unarchive() {
        this.archived = false;
    }

    private static String requireValidName(String candidate) {
        String trimmed = Objects.requireNonNull(candidate, "name").strip();
        if (trimmed.isEmpty() || trimmed.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "An account name must be 1 to " + NAME_MAX_LENGTH + " characters when trimmed");
        }
        return trimmed;
    }

    private static String requireValidLastFour(String candidate) {
        if (candidate == null) {
            return null;
        }
        if (!LAST_FOUR.matcher(candidate).matches()) {
            // Deliberately does not quote the value: it is a partial financial identifier and this
            // message can reach a log or a problem detail (ADR-0028).
            throw new IllegalArgumentException("lastFour must be exactly four ASCII digits");
        }
        return candidate;
    }

    @Override
    public boolean equals(Object other) {
        // Reflexive even before the row exists: an unsaved entity must still equal
        // itself, or putting one in a Set loses it.
        return this == other || (other instanceof Account that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return Account.class.hashCode();
    }

    /**
     * Neither the name nor {@code lastFour} appears here. The name is whatever the user typed and
     * belongs in the database rather than the log stream, and {@code lastFour} is a partial
     * financial identifier (ADR-0028) — a record's generated {@code toString} printing a secret
     * into a DEBUG line is a bug this codebase has already had once (docs/memory/gotchas.md).
     */
    @Override
    public String toString() {
        return "Account[id=" + id + ", type=" + type + ", archived=" + archived + "]";
    }
}
