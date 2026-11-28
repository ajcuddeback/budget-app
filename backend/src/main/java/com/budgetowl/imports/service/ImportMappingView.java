package com.budgetowl.imports.service;

import com.budgetowl.imports.domain.CsvColumnRole;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One account's saved CSV mapping.
 *
 * @param labels the bank's labels for the mapped columns when it was saved, by column index
 */
public record ImportMappingView(
        UUID accountId,
        String delimiter,
        String charset,
        int headerRow,
        String dateFormat,
        String decimalSeparator,
        String groupingSeparator,
        boolean invertSign,
        Map<String, CsvColumnRole> columns,
        String directionIn,
        String directionOut,
        Map<String, String> labels,
        Instant updatedAt,
        long version) {

    @Override
    public String toString() {
        return "ImportMappingView[accountId=" + accountId + "]";
    }
}
