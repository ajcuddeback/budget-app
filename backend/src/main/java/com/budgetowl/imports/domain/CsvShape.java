package com.budgetowl.imports.domain;

import java.util.List;

/**
 * What the mapping step shows the person about their CSV: how many columns, what the header says,
 * the first few records, and what the server suggests.
 *
 * <p>Every cell here is the person's own data returned to them, normalised and cut to 64
 * characters. It is never logged and never stored; {@link #toString} prints counts only.
 *
 * @param headerLabels the labels in the header row used to read the file, or empty without one
 * @param firstRecords the first five data records
 * @param suggested what the server would choose, which is <b>never applied</b> until the person has
 *     seen it and sent it back
 */
public record CsvShape(
        int columnCount,
        List<String> headerLabels,
        List<List<String>> firstRecords,
        CsvSuggestion suggested) {

    public CsvShape {
        headerLabels = List.copyOf(headerLabels);
        firstRecords = firstRecords.stream().map(List::copyOf).toList();
    }

    @Override
    public String toString() {
        return "CsvShape[columns=" + columnCount + ", records=" + firstRecords.size() + "]";
    }
}
