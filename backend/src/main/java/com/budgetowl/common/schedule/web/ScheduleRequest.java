package com.budgetowl.common.schedule.web;

import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.InvalidRequestException;
import com.budgetowl.common.schedule.Recurrence;
import com.budgetowl.common.schedule.ScheduleKind;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;

/**
 * A schedule, as a request body carries one.
 *
 * <p>One record for all five kinds, with the parameters each needs and the others left null. A
 * class per kind and a Jackson type discriminator would be purer, and would cost a client a
 * polymorphic body to build a form that has one dropdown and two inputs.
 *
 * <p><b>The combination is checked here, not only by Bean Validation</b>, which cannot say "a
 * monthly bill needs a day of the month and no weekday". {@code ck_bills_schedule_parameters} says
 * the same thing in the database, because a {@code MONTHLY} bill carrying a weekday and no day of
 * month projects nothing, silently, and looks like a bill nobody set up.
 */
public record ScheduleRequest(
        @NotNull ScheduleKind kind,
        @Min(1) @Max(31) Integer dayOfMonth,
        @Min(1) @Max(12) Integer month,
        DayOfWeek dayOfWeek,
        LocalDate anchorDate) {

    /**
     * @throws InvalidRequestException {@code validation-failed}, naming the field that is missing
     *     or the one that does not belong
     */
    public Recurrence toRecurrence() {
        return switch (kind) {
            case MONTHLY -> {
                require(
                        dayOfMonth != null,
                        "dayOfMonth",
                        "a monthly schedule needs a day of the month");
                refuse(month == null, "month");
                refuse(dayOfWeek == null, "dayOfWeek");
                refuse(anchorDate == null, "anchorDate");
                yield Recurrence.monthly(dayOfMonth);
            }
            case YEARLY -> {
                require(dayOfMonth != null, "dayOfMonth", "a yearly schedule needs a day");
                require(month != null, "month", "a yearly schedule needs a month");
                refuse(dayOfWeek == null, "dayOfWeek");
                refuse(anchorDate == null, "anchorDate");
                yield Recurrence.yearly(month, dayOfMonth);
            }
            case WEEKLY -> {
                require(dayOfWeek != null, "dayOfWeek", "a weekly schedule needs a weekday");
                refuse(dayOfMonth == null, "dayOfMonth");
                refuse(month == null, "month");
                refuse(anchorDate == null, "anchorDate");
                yield Recurrence.weekly(dayOfWeek);
            }
            case FORTNIGHTLY -> {
                require(dayOfWeek != null, "dayOfWeek", "a fortnightly schedule needs a weekday");
                // Without an anchor, "every other Wednesday" names two different sets of
                // Wednesdays and nothing says which. Required rather than defaulted to today,
                // which would make the same request mean different things on different days.
                require(
                        anchorDate != null,
                        "anchorDate",
                        "a fortnightly schedule needs a date to count from");
                refuse(dayOfMonth == null, "dayOfMonth");
                refuse(month == null, "month");
                yield Recurrence.fortnightly(dayOfWeek, anchorDate);
            }
            case IRREGULAR -> {
                refuse(dayOfMonth == null, "dayOfMonth");
                refuse(month == null, "month");
                refuse(dayOfWeek == null, "dayOfWeek");
                refuse(anchorDate == null, "anchorDate");
                yield Recurrence.irregular();
            }
        };
    }

    private static void require(boolean present, String field, String detail) {
        if (!present) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED, detail, Map.of("field", field));
        }
    }

    /** A parameter the kind has no use for is refused, not ignored: ignoring it loses an edit. */
    private static void refuse(boolean absent, String field) {
        if (!absent) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "this schedule has no use for that field",
                    Map.of("field", field));
        }
    }
}
