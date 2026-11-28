package com.budgetowl.bill.service;

import com.budgetowl.bill.domain.OccurrenceStatus;
import com.budgetowl.bill.domain.StatusSource;
import com.budgetowl.common.Money;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One occurrence as a surface sees it.
 *
 * @param recorded false for the common case: no row exists and the occurrence is {@code DUE}
 *     because that is what the schedule implies, not because anybody said so (ADR-0032)
 * @param overdue derived from {@code dueDate} and today, per request. There is no stored overdue
 *     flag, because one would need a job to stay true and would therefore eventually be false
 * @param surprising whether a paid amount is further from what was budgeted than the bill's
 *     tolerance allows. False for a bill with a fixed amount that was paid as expected, and false
 *     for anything unpaid — a bill that has not been paid cannot have been paid oddly
 * @param version null for a projected occurrence, which has no row to be stale against
 */
public record OccurrenceView(
        UUID billId,
        LocalDate dueDate,
        Money expectedAmount,
        OccurrenceStatus status,
        Money actualAmount,
        LocalDate paidDate,
        UUID transactionId,
        StatusSource statusSource,
        boolean recorded,
        boolean overdue,
        boolean surprising,
        Long version) {

    /** Redacted — see {@code BillView}. */
    @Override
    public String toString() {
        return "OccurrenceView[dueDate=" + dueDate + ", status=" + status + "]";
    }
}
