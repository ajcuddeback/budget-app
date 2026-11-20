package com.budgetowl.bill.domain;

/**
 * Where a bill stands in one period.
 *
 * <p><b>There is no {@code OVERDUE}.</b> Overdue is {@code DUE} with a due date in the past, and it
 * is computed per request. A stored overdue flag needs a job to stay true, and a status that needs
 * a job to stay true will eventually be wrong (ADR-0025) — on a self-hosted box that was switched
 * off, it would be wrong silently.
 */
public enum OccurrenceStatus {

    /** Not yet settled. Overdue if its due date has passed. */
    DUE,

    PAID,

    /** It did not apply this period. Not the same as unpaid, and not a gap in the record. */
    SKIPPED
}
