package com.budgetowl.imports.domain;

/**
 * What one CSV column means, as the person mapped it (docs/features/file-import.md rule 5, "CSV").
 *
 * <p>{@link #DATE} exactly once. Then either one signed {@link #AMOUNT}; or {@link #DEBIT} plus
 * {@link #CREDIT}, two unsigned columns of which exactly one is filled in per row; or {@link
 * #AMOUNT} plus {@link #DIRECTION}, whose two values say which way the money went. Optionally one
 * {@link #PAYEE}, up to two {@link #NOTE}s, one {@link #REFERENCE} (a bank-supplied transaction id,
 * used for duplicate detection) and one {@link #CURRENCY} (each row must be the account's).
 */
public enum CsvColumnRole {
    DATE,
    AMOUNT,
    DEBIT,
    CREDIT,
    DIRECTION,
    PAYEE,
    NOTE,
    REFERENCE,
    CURRENCY
}
