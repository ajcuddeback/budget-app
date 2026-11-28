package com.budgetowl.imports.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of a file, read and interpreted: a date and an exact amount, or the reason it has none.
 *
 * @param kind whether it can be imported, moves no money, or could not be read
 * @param payee normalised and cut to the payee limit, or {@code null} for none
 * @param payeeCut whether {@code payee} was cut, which the preview must say (rule 8)
 * @param note normalised, cut, and dropped when it only repeats the payee
 * @param payeeKey the case-folded, uncut payee, for duplicate fingerprints
 * @param identifier OFX {@code FITID} or CSV {@code REFERENCE}, stripped, or {@code null}
 * @param problem why the row could not be read, when {@code kind} is {@code UNREADABLE}
 * @param column the CSV column the problem is in, or {@code null}
 * @param text the offending cell, normalised and cut to 64 characters, so the person can find it.
 *     Returned to them and <b>never logged</b>
 */
public record ReadRow(
        int record,
        Kind kind,
        LocalDate date,
        BigDecimal amount,
        String payee,
        boolean payeeCut,
        String note,
        boolean noteCut,
        String payeeKey,
        String identifier,
        RowProblem problem,
        Integer column,
        String text) {

    public enum Kind {
        READY,
        ZERO,
        UNREADABLE
    }

    /** A bank statement row: printed as its number and kind only. */
    @Override
    public String toString() {
        return "ReadRow[record=" + record + ", kind=" + kind + "]";
    }
}
