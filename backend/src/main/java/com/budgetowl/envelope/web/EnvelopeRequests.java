package com.budgetowl.envelope.web;

import com.budgetowl.common.Change;
import com.budgetowl.envelope.domain.Envelope;
import com.budgetowl.envelope.domain.EnvelopeEntry;
import com.budgetowl.payee.domain.Payee;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * Request bodies for the envelope endpoints. Entities are never request bodies.
 *
 * <p><b>No household id.</b> It comes from the authenticated caller's verified membership
 * (ADR-0017), and no currency is accepted either: an envelope is denominated in its household's
 * base currency, and a field here would be a second source for a fact the household owns.
 *
 * <p><b>Amounts are strings</b> (ADR-0006), shape-checked here so a malformed one is a {@code
 * validation-failed} naming the field rather than a {@code malformed-request} naming nothing.
 *
 * <p>Every {@code toString} is redacted: Spring MVC logs the deserialized body at {@code DEBUG},
 * and these carry amounts, payees and whatever somebody wrote in a note.
 */
public final class EnvelopeRequests {

    /** Unsigned: an envelope amount is never negative, so the sign is not part of the shape. */
    private static final String AMOUNT = "^\\d{1,15}(\\.\\d{1,4})?$";

    private static final String AMOUNT_MESSAGE =
            "must be a positive decimal with at most 4 decimal places";

    private static final String NOT_BLANK = ".*\\S.*";

    private EnvelopeRequests() {}

    /**
     * @param period the month this envelope is for, as {@code 2026-08}. A label, not a boundary
     */
    public record CreateEnvelopeRequest(
            @NotBlank @Size(max = Envelope.NAME_MAX_LENGTH) String name,
            @NotNull YearMonth period,
            @NotNull @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String amount,
            UUID categoryId) {

        /** Redacted — see the class Javadoc. */
        @Override
        public String toString() {
            return "CreateEnvelopeRequest[period=" + period + "]";
        }
    }

    /**
     * A partial update.
     *
     * <p>A class with setters because an absent {@code categoryId} and an explicit {@code null} are
     * different instructions. There is no {@code period}: moving an envelope between months would
     * carry its entries with it and change what two periods' totals mean.
     */
    public static final class UpdateEnvelopeRequest {

        @Size(max = Envelope.NAME_MAX_LENGTH)
        @Pattern(regexp = NOT_BLANK, flags = Pattern.Flag.DOTALL, message = "must not be blank")
        private String name;

        @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE)
        private String amount;

        private UUID categoryId;

        private boolean categoryMentioned;

        /** Required: optimistic locking only works if the caller says which version it read. */
        @NotNull private Long version;

        public void setName(String name) {
            this.name = name;
        }

        public void setAmount(String amount) {
            this.amount = amount;
        }

        /** Called by Jackson only when the property is present — that is the whole mechanism. */
        public void setCategoryId(UUID categoryId) {
            this.categoryId = categoryId;
            this.categoryMentioned = true;
        }

        public void setVersion(Long version) {
            this.version = version;
        }

        public String name() {
            return name;
        }

        public String amount() {
            return amount;
        }

        public Long version() {
            return version;
        }

        public Change<UUID> categoryIdChange() {
            return Change.of(categoryId, categoryMentioned);
        }

        /** Redacted — see the class Javadoc. */
        @Override
        public String toString() {
            return "UpdateEnvelopeRequest[version=" + version + "]";
        }
    }

    /**
     * Something to log against an envelope.
     *
     * <p><b>Exactly one of {@code amount} and {@code transactionId}</b>, checked in the service
     * because neither is required on its own and Bean Validation cannot express "one of these". An
     * amount is a typed entry; a transaction id attaches one the person already recorded, and the
     * figure then comes from the transaction.
     *
     * @param date optional: today for a typed entry, the transaction's own date when attaching
     */
    public record CreateEntryRequest(
            @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String amount,
            UUID transactionId,
            LocalDate date,
            @Size(max = Payee.NAME_MAX_LENGTH) String payeeName,
            @Size(max = EnvelopeEntry.NOTE_MAX_LENGTH) String note) {

        /** Redacted — see the class Javadoc. */
        @Override
        public String toString() {
            return "CreateEntryRequest[attaching=" + (transactionId != null) + "]";
        }
    }

    /**
     * A correction to a log entry.
     *
     * <p>No {@code envelopeId} and no {@code transactionId}: moving an entry changes two remaining
     * figures at once, and re-pointing the transaction would make the entry's figure disagree with
     * the one it claims to have come from. Both are a delete and a re-log.
     */
    public static final class UpdateEntryRequest {

        private LocalDate date;

        @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE)
        private String amount;

        @Size(max = Payee.NAME_MAX_LENGTH)
        @Pattern(regexp = NOT_BLANK, flags = Pattern.Flag.DOTALL, message = "must not be blank")
        private String payeeName;

        private boolean payeeMentioned;

        @Size(max = EnvelopeEntry.NOTE_MAX_LENGTH)
        private String note;

        private boolean noteMentioned;

        @NotNull private Long version;

        public void setDate(LocalDate date) {
            this.date = date;
        }

        public void setAmount(String amount) {
            this.amount = amount;
        }

        public void setPayeeName(String payeeName) {
            this.payeeName = payeeName;
            this.payeeMentioned = true;
        }

        public void setNote(String note) {
            this.note = note;
            this.noteMentioned = true;
        }

        public void setVersion(Long version) {
            this.version = version;
        }

        public LocalDate date() {
            return date;
        }

        public String amount() {
            return amount;
        }

        public Long version() {
            return version;
        }

        public Change<String> payeeNameChange() {
            return Change.of(payeeName, payeeMentioned);
        }

        /** A blank note is cleared rather than stored: the CHECK refuses an empty one. */
        public Change<String> noteChange() {
            if (!noteMentioned) {
                return Change.unchanged();
            }
            return note == null || note.isBlank() ? Change.cleared() : Change.setTo(note);
        }

        /** Redacted — see the class Javadoc. */
        @Override
        public String toString() {
            return "UpdateEntryRequest[version=" + version + "]";
        }
    }
}
