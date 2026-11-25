package com.budgetowl.imports.domain;

import com.budgetowl.imports.domain.ImportAmountLexer.Direction;

/**
 * One transaction as a reader found it: text, not yet a date or an amount.
 *
 * <p>Readers only take a file apart. Everything that decides what a cell <em>means</em> — dates,
 * amounts, normalisation, truncation — happens once, afterwards, in {@link StatementInterpreter},
 * so the rules cannot drift between four formats.
 *
 * @param record the 1-based record number in the file: the transaction's position for OFX and QIF,
 *     the physical CSV record (counting the header) for CSV, so the person can find it
 * @param amount the amount text. For a CSV debit/credit pair, whichever was filled in
 * @param alternateAmount QIF's {@code U} line, which must agree with {@code T} when both are
 *     present
 * @param direction a sign from a CSV debit, credit or direction column
 * @param identifier OFX {@code FITID} or a CSV {@code REFERENCE}, for duplicate detection only
 * @param currency a CSV {@code CURRENCY} cell, which must be the account's
 * @param problem a problem the reader already found (a missing field, a short row, an unknown
 *     entity), or {@code null}
 * @param problemColumn the CSV column the problem is in, or {@code null}
 * @param problemText the offending text, for the person to find it in their file, or {@code null}
 * @param dateColumn the CSV column the date came from, so a later date problem can name it
 * @param amountColumn the CSV column the amount came from, likewise
 */
public record RawRow(
        int record,
        String date,
        String amount,
        String alternateAmount,
        Direction direction,
        String payee,
        String note,
        String identifier,
        String currency,
        RowProblem problem,
        Integer problemColumn,
        String problemText,
        Integer dateColumn,
        Integer amountColumn) {

    /** A row that is already known to be unreadable. */
    public static RawRow unreadable(int record, RowProblem problem, Integer column, String text) {
        return new RawRow(
                record,
                null,
                null,
                null,
                Direction.NONE,
                null,
                null,
                null,
                null,
                problem,
                column,
                text,
                null,
                null);
    }

    /** Statement content: printed as its record number only. */
    @Override
    public String toString() {
        return "RawRow[record=" + record + "]";
    }
}
