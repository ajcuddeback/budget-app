package com.budgetowl.checkin.web;

import com.budgetowl.checkin.domain.AnswerChoice;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request bodies for the check-in. <b>Entities are never request bodies.</b>
 *
 * <p><b>No household id, no user and no free text anywhere</b> in this API: the first two come from
 * the caller's verified membership (ADR-0017), and there is nothing a person types. Unknown
 * properties are refused ({@code FAIL_ON_UNKNOWN_PROPERTIES}), so sending any of them is a {@code
 * 400}. The only amount is the allocation, a string (ADR-0006), and it must equal the server's
 * surplus.
 *
 * <p>Every {@code toString} is redacted: Spring MVC logs the deserialized body at {@code DEBUG}.
 */
public final class CheckInRequests {

    /** Unsigned, at most four decimal places. The currency's own scale is checked in service. */
    private static final String AMOUNT = "^\\d{1,15}(\\.\\d{1,4})?$";

    private CheckInRequests() {}

    /**
     * One answer. Which fields a choice takes is the item's kind's; anything else is refused.
     *
     * @param version absent to create, the answer's own version to replace
     */
    public record AnswerRequest(
            @NotNull AnswerChoice choice,
            UUID categoryId,
            UUID billId,
            LocalDate dueDate,
            UUID goalId,
            UUID debtId,
            @Pattern(regexp = AMOUNT, message = "must be a decimal with at most 4 decimal places")
                    String amount,
            Boolean remember,
            Boolean recordDebtPayment,
            @PositiveOrZero Long version) {

        @Override
        public String toString() {
            return "AnswerRequest[choice=" + choice + "]";
        }
    }

    /**
     * Finish. Both fields are what the person last saw: the version proves they saw every answer,
     * the basis that the week has not moved underneath them.
     */
    public record CompleteRequest(
            @NotNull @PositiveOrZero Long version,
            @NotNull
                    @Pattern(
                            regexp = "^[0-9a-f]{64}$",
                            message = "must be a basis this server issued")
                    String basis) {

        @Override
        public String toString() {
            return "CompleteRequest[version=" + version + "]";
        }
    }
}
