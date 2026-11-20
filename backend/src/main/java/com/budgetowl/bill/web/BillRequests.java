package com.budgetowl.bill.web;

import com.budgetowl.bill.domain.Bill;
import com.budgetowl.bill.domain.OccurrenceStatus;
import com.budgetowl.common.Change;
import com.budgetowl.common.schedule.web.ScheduleRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request bodies for the bill endpoints. Entities are never request bodies.
 *
 * <p><b>No household id</b> — it comes from the authenticated caller's verified membership
 * (ADR-0017) — and no currency: a bill is denominated in its household's base currency, and a field
 * here would be a second source for a fact the household owns.
 *
 * <p><b>Amounts are strings</b> (ADR-0006), shape-checked here so a malformed one is a {@code
 * validation-failed} naming the field rather than a {@code malformed-request} naming nothing.
 *
 * <p>Every {@code toString} is redacted: Spring MVC logs the deserialized body at {@code DEBUG},
 * and these carry what somebody pays for their home.
 */
public final class BillRequests {

    /** Unsigned: a bill is money leaving, and the direction is the table, not the sign. */
    private static final String AMOUNT = "^\\d{1,15}(\\.\\d{1,4})?$";

    private static final String AMOUNT_MESSAGE =
            "must be a positive decimal with at most 4 decimal places";

    private static final String NOT_BLANK = ".*\\S.*";

    private BillRequests() {}

    public record CreateBillRequest(
            @NotBlank @Size(max = Bill.NAME_MAX_LENGTH) String name,
            @NotNull @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String expectedAmount,
            @NotNull @Valid ScheduleRequest schedule,
            UUID categoryId,
            /**
             * Boxed, and absent means false.
             *
             * <p>Not a primitive: Jackson refuses a record whose primitive component is missing
             * from the body rather than defaulting it, so {@code boolean} here made every request
             * that left the field out a {@code malformed-request} — and most requests leave it out
             * (docs/memory/gotchas.md).
             */
            Boolean amountVaries,
            @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String tolerance) {

        /** Absent means a bill whose amount is what it says it is, which is the common case. */
        public boolean varies() {
            return Boolean.TRUE.equals(amountVaries);
        }

        /** Redacted — see the class Javadoc. */
        @Override
        public String toString() {
            return "CreateBillRequest[schedule="
                    + (schedule == null ? null : schedule.kind())
                    + "]";
        }
    }

    /**
     * A partial update.
     *
     * <p>A class with setters because an absent {@code categoryId} and an explicit {@code null} are
     * different instructions.
     */
    public static final class UpdateBillRequest {

        @Size(max = Bill.NAME_MAX_LENGTH)
        @Pattern(regexp = NOT_BLANK, flags = Pattern.Flag.DOTALL, message = "must not be blank")
        private String name;

        @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE)
        private String expectedAmount;

        @Valid private ScheduleRequest schedule;

        private UUID categoryId;

        private boolean categoryMentioned;

        private Boolean amountVaries;

        @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE)
        private String tolerance;

        /** Retiring a bill: it stops projecting and keeps every period already recorded. */
        private Boolean active;

        /** Required: optimistic locking only works if the caller says which version it read. */
        @NotNull private Long version;

        public void setName(String name) {
            this.name = name;
        }

        public void setExpectedAmount(String expectedAmount) {
            this.expectedAmount = expectedAmount;
        }

        public void setSchedule(ScheduleRequest schedule) {
            this.schedule = schedule;
        }

        /** Called by Jackson only when the property is present — that is the whole mechanism. */
        public void setCategoryId(UUID categoryId) {
            this.categoryId = categoryId;
            this.categoryMentioned = true;
        }

        public void setAmountVaries(Boolean amountVaries) {
            this.amountVaries = amountVaries;
        }

        public void setTolerance(String tolerance) {
            this.tolerance = tolerance;
        }

        public void setActive(Boolean active) {
            this.active = active;
        }

        public void setVersion(Long version) {
            this.version = version;
        }

        public String name() {
            return name;
        }

        public String expectedAmount() {
            return expectedAmount;
        }

        public ScheduleRequest schedule() {
            return schedule;
        }

        public Boolean amountVaries() {
            return amountVaries;
        }

        public String tolerance() {
            return tolerance;
        }

        public Boolean active() {
            return active;
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
            return "UpdateBillRequest[version=" + version + "]";
        }
    }

    /**
     * What a household is recording about one occurrence.
     *
     * <p><b>No version</b>: see {@code StatusRecord}. {@code actualAmount} and {@code paidDate}
     * belong to {@code PAID} and are both optional there — the one-tap case is "I paid it", which
     * means what was budgeted, today.
     */
    public record RecordStatusRequest(
            @NotNull OccurrenceStatus status,
            @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String actualAmount,
            LocalDate paidDate) {

        /** Redacted — see the class Javadoc. */
        @Override
        public String toString() {
            return "RecordStatusRequest[status=" + status + "]";
        }
    }
}
