package com.budgetowl.imports.service;

import com.budgetowl.imports.domain.CsvColumnRole;
import java.util.Map;

/**
 * The {@code options} of a preview or commit, as the caller sent them. Every field may be absent;
 * which ones are allowed depends on the format, and {@link ImportSettingsReader} decides.
 *
 * <p>For OFX only {@code charset} and {@code invertSign} apply. QIF also takes {@code dateFormat},
 * because QIF dates are as ambiguous as CSV's. The rest is CSV only, and sending it for another
 * format is refused rather than ignored.
 */
public record ImportSettings(
        String charset,
        String delimiter,
        Integer headerRow,
        String dateFormat,
        String decimalSeparator,
        String groupingSeparator,
        Boolean invertSign,
        Map<String, CsvColumnRole> columns,
        String directionIn,
        String directionOut,
        Boolean saveMapping) {

    public static final ImportSettings NONE =
            new ImportSettings(null, null, null, null, null, null, null, null, null, null, null);

    public boolean inverts() {
        return Boolean.TRUE.equals(invertSign);
    }

    public boolean saves() {
        return Boolean.TRUE.equals(saveMapping);
    }

    /** Settings only — but direction values are the bank's words, so they are not printed. */
    @Override
    public String toString() {
        return "ImportSettings[columns=" + (columns == null ? 0 : columns.size()) + "]";
    }
}
