package com.budgetowl.transaction.web;

import com.budgetowl.common.Change;
import com.budgetowl.payee.domain.Payee;
import com.budgetowl.transaction.domain.Transaction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request bodies for the transaction endpoints. Entities are never request bodies.
 *
 * <p><b>No household id.</b> It comes from the authenticated caller's verified membership
 * (ADR-0017, non-negotiable #2).
 *
 * <p><b>Amounts are strings and are shape-checked here</b> (ADR-0006), not parsed by Jackson: Bean
 * Validation is what produces a {@code validation-failed} naming the field, while a deserializer
 * failure can only be a {@code malformed-request} naming nothing. {@code MoneyJsonModule} refuses a
 * JSON number for any amount declared this way — a {@code double} cannot hold every {@code
 * NUMERIC(19,4)} and the one that gets rounded will be somebody's money.
 *
 * <p><b>No currency field.</b> The amount is in the account's currency, read from the account. A
 * field here would be a second source for a fact the account owns, and the two would disagree.
 *
 * <p><b>{@code RECONCILED} is not accepted from a caller.</b> It means a statement period was
 * closed against this row, which is reconciliation's to say (slice 13) and not a user's. The
 * contract cannot express it, the same way {@code kind} and {@code accountId} cannot express their
 * forbidden changes — rather than accepting it and apologising.
 *
 * <p>Every {@code toString} is redacted: Spring MVC logs the deserialized body at {@code DEBUG},
 * and these records hold an amount, a payee and whatever the user wrote in the note.
 */
public final class TransactionRequests {

    /**
     * A signed decimal with at most four places, and at most 15 digits before the point so the
     * value fits {@code NUMERIC(19,4)}. Leading {@code +} is accepted because a person stating an
     * income explicitly is being clear, not wrong.
     */
    private static final String AMOUNT = "^[+-]?\\d{1,15}(\\.\\d{1,4})?$";

    private static final String AMOUNT_MESSAGE =
            "must be a signed decimal with at most 4 decimal places";

    private static final String NOT_BLANK = ".*\\S.*";

    private TransactionRequests() {}

    /**
     * @param payeeName optional, and created on first use — recording a purchase must not require
     *     managing a payee list first
     * @param categoryId optional: absent is "Needs a category", a normal state and not an error
     * @param status optional, defaulting to {@code CLEARED}
     */
    public record CreateTransactionRequest(
            @NotNull UUID accountId,
            @NotNull LocalDate date,
            @NotNull @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String amount,
            @Size(max = Payee.NAME_MAX_LENGTH) String payeeName,
            UUID categoryId,
            @Size(max = Transaction.NOTE_MAX_LENGTH) String note,
            @Pattern(regexp = "PENDING|CLEARED") String status) {

        /** Redacted — see the class Javadoc. */
        @Override
        public String toString() {
            return "CreateTransactionRequest[accountId=" + accountId + ", date=" + date + "]";
        }
    }

    /**
     * A partial update.
     *
     * <p>A class with setters rather than a record, because for {@code payeeName}, {@code
     * categoryId} and {@code note} an absent key and an explicit {@code null} are different
     * instructions — resolved through {@link Change}. Without that, a client PATCHing an amount
     * would silently clear all three.
     *
     * <p>There is no {@code accountId}: moving a transaction between accounts is not an edit, so
     * the contract cannot express it.
     */
    public static final class UpdateTransactionRequest {

        private LocalDate date;

        @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE)
        private String amount;

        @Size(max = Payee.NAME_MAX_LENGTH)
        @Pattern(regexp = NOT_BLANK, flags = Pattern.Flag.DOTALL, message = "must not be blank")
        private String payeeName;

        private boolean payeeMentioned;

        private UUID categoryId;

        private boolean categoryMentioned;

        @Size(max = Transaction.NOTE_MAX_LENGTH)
        private String note;

        private boolean noteMentioned;

        @Pattern(regexp = "PENDING|CLEARED")
        private String status;

        /** Required: optimistic locking only works if the caller says which version it read. */
        @NotNull private Long version;

        public void setDate(LocalDate date) {
            this.date = date;
        }

        public void setAmount(String amount) {
            this.amount = amount;
        }

        /** Called by Jackson only when the property is present — that is the whole mechanism. */
        public void setPayeeName(String payeeName) {
            this.payeeName = payeeName;
            this.payeeMentioned = true;
        }

        public void setCategoryId(UUID categoryId) {
            this.categoryId = categoryId;
            this.categoryMentioned = true;
        }

        public void setNote(String note) {
            this.note = note;
            this.noteMentioned = true;
        }

        public void setStatus(String status) {
            this.status = status;
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

        public String status() {
            return status;
        }

        public Long version() {
            return version;
        }

        public Change<String> payeeNameChange() {
            return Change.of(payeeName, payeeMentioned);
        }

        public Change<UUID> categoryIdChange() {
            return Change.of(categoryId, categoryMentioned);
        }

        /**
         * A blank note is {@link Change#cleared()} rather than a stored empty string: {@code
         * ck_transactions_note} refuses an empty note, and a user clearing the field means to
         * remove it.
         */
        public Change<String> noteChange() {
            if (!noteMentioned) {
                return Change.unchanged();
            }
            return note == null || note.isBlank() ? Change.cleared() : Change.setTo(note);
        }

        /** Redacted — see the class Javadoc. */
        @Override
        public String toString() {
            return "UpdateTransactionRequest[version=" + version + "]";
        }
    }
}
