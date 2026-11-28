package com.budgetowl.imports.domain;

/**
 * Why one row could not be read. These appear only inside a preview's {@code attention} list; a row
 * problem never refuses the file.
 */
public enum RowProblem {
    DATE_UNREADABLE("row-date-unreadable"),
    DATE_AMBIGUOUS("row-date-ambiguous"),
    DATE_OUT_OF_RANGE("row-date-out-of-range"),
    AMOUNT_UNREADABLE("row-amount-unreadable"),
    AMOUNT_TOO_PRECISE("row-amount-too-precise"),
    AMOUNT_TOO_LARGE("row-amount-too-large"),
    CURRENCY_MISMATCH("row-currency-mismatch"),
    MISSING_FIELD("row-missing-field"),
    COLUMNS_SHORT("row-columns-short"),
    ENTITY_UNKNOWN("row-entity-unknown");

    private final String code;

    RowProblem(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
