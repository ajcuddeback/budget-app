package com.budgetowl.envelope.domain;

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
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One purchase logged against an envelope.
 *
 * <p><b>An amount alone is a valid entry.</b> Payee optional, note optional, category inherited
 * from the envelope. This is the action somebody takes eighty times a month, usually one-handed,
 * usually in a queue — anything that makes more than the amount mandatory has broken the feature
 * ({@code docs/features/envelopes.md}).
 *
 * <p><b>The amount is POSITIVE</b>, which is the difference from {@link
 * com.budgetowl.transaction.domain.Transaction}. A transaction records which way money went, so it
 * is signed. An entry records how much of a decided amount was used, which only goes one way; a
 * sign here would make {@code remaining} ambiguous.
 *
 * <p>Like the other financial entities this carries no currency — it is denominated by its
 * envelope, which is denominated by the household.
 */
@Entity
@Table(name = "envelope_entries")
public class EnvelopeEntry implements HouseholdOwned {

    /** 1–500 characters after trimming, matching {@code ck_envelope_entries_note}. */
    public static final int NOTE_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    /**
     * Fixed at creation. Moving an entry between envelopes changes two remaining figures at once;
     * the user deletes it and logs it where they meant.
     */
    @Column(name = "envelope_id", nullable = false, updatable = false)
    private UUID envelopeId;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "payee_id")
    private UUID payeeId;

    @Column(name = "note")
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false)
    private EntrySource source;

    /**
     * The transaction a person attached, for a {@link EntrySource#SYNCED} entry.
     *
     * <p>Nullable even when the source is {@code SYNCED}: deleting the transaction detaches the
     * entry and leaves the amount, because somebody decided that money came out of this envelope
     * and the plan does not stop being true because the record of the purchase was removed.
     */
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

    /** For Hibernate and the factories. Does nothing and cannot throw. */
    protected EnvelopeEntry() {
        // Hibernate, and the factories below.
    }

    /**
     * An entry somebody typed.
     *
     * @param envelope the envelope it is logged against, required — a {@code UUID} parameter would
     *     accept an id a caller supplied, which is never evidence of ownership
     * @param amount positive, in the household's base currency
     */
    public static EnvelopeEntry typedInto(
            Envelope envelope, LocalDate date, Money amount, Currency baseCurrency) {
        EnvelopeEntry entry = blank(envelope, date);
        entry.source = EntrySource.MANUAL;
        entry.correctAmount(amount, baseCurrency);
        return entry;
    }

    /**
     * An entry a person created by attaching a transaction they had already recorded.
     *
     * <p>The amount comes from the transaction rather than from the caller: that is what makes the
     * two agree, and what makes the entry worth marking as {@code SYNCED} at all.
     *
     * @param spent the transaction's amount as money <em>out</em> — positive. The caller converts,
     *     because only it knows that a transaction's sign means direction
     */
    public static EnvelopeEntry attachedTo(
            Envelope envelope,
            UUID transactionId,
            LocalDate date,
            Money spent,
            Currency baseCurrency) {
        EnvelopeEntry entry = blank(envelope, date);
        entry.source = EntrySource.SYNCED;
        entry.transactionId = Objects.requireNonNull(transactionId, "transactionId");
        entry.correctAmount(spent, baseCurrency);
        return entry;
    }

    private static EnvelopeEntry blank(Envelope envelope, LocalDate date) {
        Objects.requireNonNull(envelope, "envelope");
        EnvelopeEntry entry = new EnvelopeEntry();
        entry.householdId = envelope.householdId();
        entry.envelopeId = envelope.id();
        entry.date = Objects.requireNonNull(date, "date");
        return entry;
    }

    public UUID id() {
        return id;
    }

    @Override
    public UUID householdId() {
        return householdId;
    }

    public UUID envelopeId() {
        return envelopeId;
    }

    public LocalDate date() {
        return date;
    }

    /** Positive, with no currency attached — see the class Javadoc. */
    public BigDecimal amount() {
        return amount;
    }

    public Optional<UUID> payeeId() {
        return Optional.ofNullable(payeeId);
    }

    public Optional<String> note() {
        return Optional.ofNullable(note);
    }

    public EntrySource source() {
        return source;
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

    public void on(LocalDate newDate) {
        this.date = Objects.requireNonNull(newDate, "date");
    }

    /**
     * @throws IllegalArgumentException if the amount is not positive, or not in the household's
     *     base currency. Zero is refused: an entry of nothing used nothing
     */
    public void correctAmount(Money corrected, Currency baseCurrency) {
        Objects.requireNonNull(corrected, "amount");
        if (!corrected.currency().equals(baseCurrency)) {
            throw new IllegalArgumentException(
                    "an envelope entry is denominated in the household's base currency");
        }
        if (!corrected.isPositive()) {
            throw new IllegalArgumentException("an envelope entry must be a positive amount");
        }
        this.amount = corrected.amount();
    }

    /** {@code null} clears the payee, which is ordinary — cash out of a machine has none. */
    public void payTo(UUID newPayeeId) {
        this.payeeId = newPayeeId;
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
}
