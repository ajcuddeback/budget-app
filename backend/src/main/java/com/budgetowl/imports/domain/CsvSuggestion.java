package com.budgetowl.imports.domain;

import java.util.List;
import java.util.Map;

/**
 * The settings the server would choose for a CSV, from its first 50 records. Shown, never applied:
 * the person confirms or changes each one and sends it back (rule 5).
 *
 * @param dateFormat the one format that reads every sampled date, or {@code null} when none does or
 *     several read them differently — see {@code dateFormatCandidates}
 * @param dateFormatCandidates every distinct reading, when there is more than one
 * @param columns a guess at each column's role, from the header labels and the cells
 */
public record CsvSuggestion(
        char delimiter,
        int headerRow,
        ImportDateFormat dateFormat,
        List<ImportDateFormat> dateFormatCandidates,
        char decimalSeparator,
        Character groupingSeparator,
        Map<Integer, CsvColumnRole> columns) {

    public CsvSuggestion {
        dateFormatCandidates = List.copyOf(dateFormatCandidates);
        columns = Map.copyOf(columns);
    }
}
