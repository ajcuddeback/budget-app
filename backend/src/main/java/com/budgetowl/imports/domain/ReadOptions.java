package com.budgetowl.imports.domain;

/**
 * What the person told us about the file.
 *
 * @param charset the charset they chose, or {@code null}. Already checked against the allowlist
 * @param dateFormat the date format they chose, or {@code null} to detect an unambiguous one
 * @param mapping the CSV mapping, or {@code null} — for a CSV, that means "describe the file and
 *     suggest one"
 * @param csvDelimiter for a CSV with no mapping yet, the delimiter to describe it with
 * @param csvHeaderRow likewise, the header row
 * @param lastFour the target account's last four digits, if it has them, to compute the account
 *     hint. Compared in memory only
 */
public record ReadOptions(
        String charset,
        ImportDateFormat dateFormat,
        boolean invertSign,
        CsvMapping mapping,
        Character csvDelimiter,
        Integer csvHeaderRow,
        String lastFour) {

    @Override
    public String toString() {
        return "ReadOptions[charset=" + charset + ", dateFormat=" + dateFormat + "]";
    }
}
