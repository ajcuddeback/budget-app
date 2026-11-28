package com.budgetowl.transaction.domain;

import com.budgetowl.account.domain.Account;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A single movement of money.
 *
 * <p><b>This entity has no currency, and therefore no {@code Money}.</b> The amount is denominated
 * by its account (see {@code V12}'s header), so {@link #amount()} returns a {@link BigDecimal} and
 * only the service — which has the account in hand — can pair the two into a {@code Money}. A
 * {@code currency} column here would be a second copy of a fact the account already owns, and the
 * two would eventually disagree; a {@code Money} field would need that column to exist. This is
 * also why {@code Account} refuses to change its currency once it has any transaction: these rows
 * would silently change meaning.
 *
 * <p><b>The sign carries the direction.</b> Negative is money leaving the account, positive is
 * money arriving. There is no stored income/expense flag, because the sign already says it and two
 * sources for one fact are two sources that can disagree. A category's {@code kind} is a different
 * fact — what the category is <em>for</em> — and a positive amount against an expense category is a
 * refund, which is ordinary and must not be rejected.
 *
 * <p><b>The parent references are plain {@code UUID}s</b>, like {@code householdId} on {@link
 * Account}: a {@code @ManyToOne} would invite lazy traversal from a row whose ownership the caller
 * never proved, and the scoping predicate has to stay a single condition on the row itself. The
 * database holds the integrity — {@code fk_transactions_account} is composite on {@code
 * (account_id, household_id)}, so a transaction physically cannot point at another household's
 * account.
 *
 * <p>This class validates what it can see rather than trusting the edge, so the same rules hold for
 * an import path that never passes through a controller.
 */
@Entity
@Table(name = "transactions")
public class Transaction implements HouseholdOwned {

    /** 1–500 characters after trimming, matching {@code ck_transactions_note}. */
    public static final int NOTE_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    /**
     * Fixed at creation. Moving a transaction between accounts is not an edit — it changes two
     * balances and, if the currencies differ, the meaning of the amount — so the service refuses it
     * and the user deletes and re-enters, or records a transfer (slice 8).
     */
    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    /**
     * A calendar date, not an instant: see {@code V12}'s note on why a timestamptz would be wrong.
     */
    @Column(name = "date", nullable = false)
    private LocalDate date;

    /** {@code NUMERIC(19,4)}, signed (ADR-0006). Never {@code double}, not even for display. */
    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "payee_id")
    private UUID payeeId;

    /** {@code null} is "Needs a category" — a real state, not missing data. */
    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "note")
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TransactionStatus status;

    @Column(name = "external_id", updatable = false)
    private String externalId;

    @Column(name = "transfer_group_id")
    private UUID transferGroupId;

    /**
     * The import that wrote this row, or {@code null} for a typed one (slice 12). Set once, at
     * creation, and never by an update: the database clears it ({@code ON DELETE SET NULL}) when
     * the batch is undone, which is the only way it changes.
     */
    @Column(name = "import_batch_id", updatable = false)
    private UUID importBatchId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Transaction() {
        // Hibernate.
    }

    /**
     * Records a movement against an account.
     *
     * @param account the account it belongs to, required — a {@code UUID} parameter here would
     *     accept an id a caller supplied, which is never evidence of ownership
     * @param amount in the account's own currency, and rejected if it is not: the amount's
     *     denomination is the account's, and a mismatch means somebody has lost track of which
     *     currency they are in
     * @throws IllegalArgumentException if the amount is zero, or is not in the account's currency
     */
    public static Transaction of(
            Account account, LocalDate date, Money amount, TransactionStatus status) {
        Objects.requireNonNull(account, "account");
        Transaction transaction = new Transaction();
        transaction.householdId = account.householdId();
        transaction.accountId = account.id();
        transaction.status = Objects.requireNonNull(status, "status");
        transaction.on(Objects.requireNonNull(date, "date"));
        transaction.correctAmount(amount, account.currency());
        return transaction;
    }

    public UUID id() {
        return id;
    }

    @Override
    public UUID householdId() {
        return householdId;
    }

    public UUID accountId() {
        return accountId;
    }

    public LocalDate date() {
        return date;
    }

    /**
     * The signed amount, with no currency attached.
     *
     * <p>Pair it with the account's currency to get a {@link Money} — see the class Javadoc for why
     * this entity cannot do that itself.
     */
    public BigDecimal amount() {
        return amount;
    }

    public Optional<UUID> payeeId() {
        return Optional.ofNullable(payeeId);
    }

    /** Absent means "Needs a category", which is a state the UI shows rather than an error. */
    public Optional<UUID> categoryId() {
        return Optional.ofNullable(categoryId);
    }

    public Optional<String> note() {
        return Optional.ofNullable(note);
    }

    public TransactionStatus status() {
        return status;
    }

    public Optional<String> externalId() {
        return Optional.ofNullable(externalId);
    }

    public Optional<UUID> transferGroupId() {
        return Optional.ofNullable(transferGroupId);
    }

    /** The import that wrote this row, if one did. Provenance, and the handle for undo. */
    public Optional<UUID> importBatchId() {
        return Optional.ofNullable(importBatchId);
    }

    /** Part of a transfer, whose other half shares its {@code transferGroupId} (slice 8). */
    public boolean isTransfer() {
        return transferGroupId != null;
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

    public void on(LocalDate newDate) {
        this.date = Objects.requireNonNull(newDate, "date");
    }

    /**
     * @throws IllegalArgumentException if zero — zero moves no money and is refused by {@code
     *     ck_transactions_amount_nonzero} too — or if the currency is not the account's
     */
    public void correctAmount(Money corrected, java.util.Currency accountCurrency) {
        Objects.requireNonNull(corrected, "amount");
        if (!corrected.currency().equals(accountCurrency)) {
            throw new IllegalArgumentException("an amount must be in its account's currency");
        }
        if (corrected.isZero()) {
            throw new IllegalArgumentException("a transaction of zero moves no money");
        }
        this.amount = corrected.amount();
    }

    /** {@code null} clears the payee. */
    public void payTo(UUID newPayeeId) {
        this.payeeId = newPayeeId;
    }

    /** {@code null} puts the transaction back into "Needs a category". */
    public void fileUnder(UUID newCategoryId) {
        this.categoryId = newCategoryId;
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

    public void markAs(TransactionStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus, "status");
    }

    /** Set when the two halves of a transfer are linked (slice 8). */
    public void linkToTransfer(UUID groupId) {
        this.transferGroupId = groupId;
    }

    /** Set by an importer so re-importing a file cannot duplicate rows (slice 12). */
    public void importedAs(String sourceId) {
        this.externalId = sourceId;
    }

    /** Records which import wrote this row (slice 12). Only meaningful before the first flush. */
    public void importedIn(UUID batchId) {
        this.importBatchId = Objects.requireNonNull(batchId, "batchId");
    }
}
