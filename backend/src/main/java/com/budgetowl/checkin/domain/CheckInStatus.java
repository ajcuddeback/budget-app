package com.budgetowl.checkin.domain;

/**
 * A stored check-in's state. {@code NOT_STARTED} is not here: a week nobody started has no row, and
 * its status is computed on read (ADR-0032's reasoning about projected occurrences).
 */
public enum CheckInStatus {
    /** Answers are being collected. Nothing they say has taken effect. */
    DRAFT,

    /** Finished. Read-only for ever: there is no reopen and no undo. */
    COMPLETED
}
