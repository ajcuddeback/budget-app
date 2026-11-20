package com.budgetowl.income.web;

import com.budgetowl.common.Change;
import com.budgetowl.common.schedule.web.ScheduleRequest;
import com.budgetowl.income.domain.IncomeSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request bodies for the income endpoints. Entities are never request bodies.
 *
 * <p>No household id and no currency, for the reasons {@code BillRequests} gives. No {@code
 * sourceOfTruth} either: it says where a figure came from, and a caller asserting that its own
 * typed number was derived from history would make the field a lie.
 *
 * <p>Every {@code toString} is redacted: these carry somebody's salary.
 */
public final class IncomeRequests {

    private static final String AMOUNT = "^\\d{1,15}(\\.\\d{1,4})?$";

    private static final String AMOUNT_MESSAGE =
            "must be a positive decimal with at most 4 decimal places";

    private static final String NOT_BLANK = ".*\\S.*";

    private IncomeRequests() {}

    public record CreateIncomeRequest(
            @NotBlank @Size(max = IncomeSource.NAME_MAX_LENGTH) String name,
            @NotNull @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String amount,
            @NotNull @Valid ScheduleRequest schedule,
            UUID accountId) {

        /** Redacted — see the class Javadoc. */
        @Override
        public String toString() {
            return "CreateIncomeRequest[schedule="
                    + (schedule == null ? null : schedule.kind())
                    + "]";
        }
    }

    /** A partial update. A class with setters, for the reason {@code BillRequests} gives. */
    public static final class UpdateIncomeRequest {

        @Size(max = IncomeSource.NAME_MAX_LENGTH)
        @Pattern(regexp = NOT_BLANK, flags = Pattern.Flag.DOTALL, message = "must not be blank")
        private String name;

        @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE)
        private String amount;

        @Valid private ScheduleRequest schedule;

        private UUID accountId;

        private boolean accountMentioned;

        private Boolean active;

        @NotNull private Long version;

        public void setName(String name) {
            this.name = name;
        }

        public void setAmount(String amount) {
            this.amount = amount;
        }

        public void setSchedule(ScheduleRequest schedule) {
            this.schedule = schedule;
        }

        /** Called by Jackson only when the property is present — that is the whole mechanism. */
        public void setAccountId(UUID accountId) {
            this.accountId = accountId;
            this.accountMentioned = true;
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

        public String amount() {
            return amount;
        }

        public ScheduleRequest schedule() {
            return schedule;
        }

        public Boolean active() {
            return active;
        }

        public Long version() {
            return version;
        }

        public Change<UUID> accountIdChange() {
            return Change.of(accountId, accountMentioned);
        }

        /** Redacted — see the class Javadoc. */
        @Override
        public String toString() {
            return "UpdateIncomeRequest[version=" + version + "]";
        }
    }
}
