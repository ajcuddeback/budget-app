package com.budgetowl.transfer.web;

import com.budgetowl.common.Change;
import com.budgetowl.transaction.domain.Transaction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request bodies for the transfer endpoints. Entities are never request bodies.
 *
 * <p><b>No household id</b> (ADR-0017), and <b>no category and no payee</b>: a transfer is not
 * spending and the other side is not a third party. They are absent from the shape rather than
 * ignored, so a client cannot believe it filed one and find later that it did not.
 *
 * <p><b>Amounts are strings</b> (ADR-0006) and <b>unsigned</b>: the direction is in the field
 * names, so nobody writes a minus and nobody can write the wrong one.
 *
 * <p>Every {@code toString} is redacted: Spring MVC logs the deserialized body at {@code DEBUG}.
 */
public final class TransferRequests {

    private static final String AMOUNT = "^\\d{1,15}(\\.\\d{1,4})?$";

    private static final String AMOUNT_MESSAGE =
            "must be a positive decimal with at most 4 decimal places";

    private TransferRequests() {}

    /**
     * @param amountReceived what arrived, when the two accounts are in different currencies.
     *     Required there and refused otherwise — checked in the service, which is the only place
     *     that knows both accounts' currencies
     */
    public record CreateTransferRequest(
            @NotNull UUID fromAccountId,
            @NotNull UUID toAccountId,
            @NotNull LocalDate date,
            @NotNull @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String amount,
            @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String amountReceived,
            @Size(max = Transaction.NOTE_MAX_LENGTH) String note) {

        /** Redacted — see the class Javadoc. */
        @Override
        public String toString() {
            return "CreateTransferRequest[date=" + date + "]";
        }
    }

    /**
     * A change to a transfer.
     *
     * <p>A class with setters because an absent {@code note} and an explicit {@code null} are
     * different instructions. <b>No account ids</b>: a transfer's accounts are fixed at creation,
     * as a transaction's are, and moving one is a delete and a re-create.
     */
    public static final class UpdateTransferRequest {

        private LocalDate date;

        @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE)
        private String amount;

        @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE)
        private String amountReceived;

        @Size(max = Transaction.NOTE_MAX_LENGTH)
        private String note;

        private boolean noteMentioned;

        /** Required: optimistic locking only works if the caller says which version it read. */
        @NotNull private Long version;

        public void setDate(LocalDate date) {
            this.date = date;
        }

        public void setAmount(String amount) {
            this.amount = amount;
        }

        public void setAmountReceived(String amountReceived) {
            this.amountReceived = amountReceived;
        }

        /** Called by Jackson only when the property is present — that is the whole mechanism. */
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

        public String amountReceived() {
            return amountReceived;
        }

        public Long version() {
            return version;
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
            return "UpdateTransferRequest[version=" + version + "]";
        }
    }
}
