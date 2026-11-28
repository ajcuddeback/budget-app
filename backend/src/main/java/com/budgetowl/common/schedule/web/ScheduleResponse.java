package com.budgetowl.common.schedule.web;

import com.budgetowl.common.schedule.Recurrence;
import com.budgetowl.common.schedule.ScheduleKind;
import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * A schedule as a client reads it: the kind, and only the parameters that kind uses.
 *
 * <p>Shared by bills and income, which is why it lives here rather than in either one's {@code web}
 * package. They schedule the same way — money leaving on a date and money arriving on one are the
 * same arithmetic — and two copies of this would drift the first time one gained a kind.
 */
public record ScheduleResponse(
        ScheduleKind kind,
        Integer dayOfMonth,
        Integer month,
        DayOfWeek dayOfWeek,
        LocalDate anchorDate) {

    public static ScheduleResponse of(Recurrence recurrence) {
        return new ScheduleResponse(
                recurrence.kind(),
                recurrence.dayOfMonth(),
                recurrence.month(),
                recurrence.dayOfWeek(),
                recurrence.anchorDate());
    }
}
