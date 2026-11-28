package com.budgetowl.bill.service;

import com.budgetowl.bill.domain.BillOccurrence;
import java.time.LocalDate;

/**
 * One occurrence of a bill in a period: the date it falls on, and the row recorded against it if
 * there is one.
 *
 * @param dueDate <b>the slot</b>: the date the schedule implies, which is what the period's list is
 *     ordered by. For a row matched to a slot by position rather than by date (ADR-0032's step 2)
 *     this is <em>not</em> the row's own due date, and {@code BillService} reports the row's —
 *     because that is the date the API addresses it at, and a client shown the projected one could
 *     not correct the record it is looking at
 * @param recorded null for the common case. A projected occurrence has no row, no id and no
 *     version, and is {@code DUE} by definition (ADR-0032)
 */
public record ProjectedOccurrence(LocalDate dueDate, BillOccurrence recorded) {

    public boolean isRecorded() {
        return recorded != null;
    }
}
