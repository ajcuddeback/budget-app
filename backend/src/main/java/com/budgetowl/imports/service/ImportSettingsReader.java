package com.budgetowl.imports.service;

import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.InvalidRequestException;
import com.budgetowl.imports.domain.CsvColumnRole;
import com.budgetowl.imports.domain.CsvMapping;
import com.budgetowl.imports.domain.ImportCharsets;
import com.budgetowl.imports.domain.ImportDateFormat;
import com.budgetowl.imports.domain.ImportFormat;
import com.budgetowl.imports.domain.ImportRefusal;
import com.budgetowl.imports.domain.ReadOptions;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns the caller's {@code options} into what the reader needs, refusing anything that does not
 * apply to the declared format (docs/features/file-import.md, "Request").
 *
 * <p>Refused rather than ignored, everywhere: a CSV delimiter sent with an OFX file is a client
 * that believes it changed something it did not.
 */
final class ImportSettingsReader {

    private ImportSettingsReader() {}

    static ReadOptions read(ImportFormat format, ImportSettings settings, String lastFour) {
        return read(format, settings, lastFour, "options.");
    }

    /**
     * @param prefix what field names in a refusal start with: {@code options.} inside a preview or
     *     commit, nothing for the saved-mapping body, whose fields are at the top level
     */
    static ReadOptions read(
            ImportFormat format, ImportSettings settings, String lastFour, String prefix) {
        ImportSettings given = settings == null ? ImportSettings.NONE : settings;
        String charset = charset(given.charset(), prefix);

        if (format == ImportFormat.OFX) {
            refuseIfPresent(given.dateFormat(), prefix + "dateFormat");
        }
        if (format != ImportFormat.CSV) {
            refuseIfPresent(given.delimiter(), prefix + "delimiter");
            refuseIfPresent(given.headerRow(), prefix + "headerRow");
            refuseIfPresent(given.decimalSeparator(), prefix + "decimalSeparator");
            refuseIfPresent(given.groupingSeparator(), prefix + "groupingSeparator");
            refuseIfPresent(given.columns(), prefix + "columns");
            refuseIfPresent(given.directionIn(), prefix + "directionIn");
            refuseIfPresent(given.directionOut(), prefix + "directionOut");
            if (given.saves()) {
                throw invalid(prefix + "saveMapping", "a saved mapping is for CSV only");
            }
        }
        ImportDateFormat dateFormat = dateFormat(given.dateFormat(), prefix);

        CsvMapping mapping = null;
        Character delimiter = null;
        if (format == ImportFormat.CSV) {
            delimiter = single(given.delimiter(), prefix + "delimiter");
            if (delimiter != null && !CsvMapping.DELIMITERS.contains(delimiter)) {
                throw invalid(prefix + "delimiter", "must be one of , ; tab |");
            }
            if (given.headerRow() != null
                    && (given.headerRow() < 0
                            || given.headerRow() > CsvMapping.MAXIMUM_HEADER_ROW)) {
                throw invalid(prefix + "headerRow", "must be between 0 and 20");
            }
            if (given.columns() != null) {
                mapping = mapping(given, delimiter, dateFormat, prefix);
            } else if (given.saves()) {
                throw invalid(prefix + "columns", "map the columns before saving the mapping");
            }
        }
        return new ReadOptions(
                charset,
                dateFormat,
                given.inverts(),
                mapping,
                delimiter,
                given.headerRow(),
                lastFour);
    }

    private static CsvMapping mapping(
            ImportSettings given, Character delimiter, ImportDateFormat dateFormat, String prefix) {
        if (delimiter == null) {
            throw invalid(prefix + "delimiter", "is required with a column mapping");
        }
        if (given.headerRow() == null) {
            throw invalid(prefix + "headerRow", "is required with a column mapping");
        }
        Character decimal = single(given.decimalSeparator(), prefix + "decimalSeparator");
        if (decimal == null) {
            throw invalid(prefix + "decimalSeparator", "is required with a column mapping");
        }
        Character grouping = single(given.groupingSeparator(), prefix + "groupingSeparator");
        Map<Integer, CsvColumnRole> columns = new HashMap<>();
        for (Map.Entry<String, CsvColumnRole> entry : given.columns().entrySet()) {
            columns.put(columnIndex(entry.getKey(), prefix), entry.getValue());
        }
        return new CsvMapping(
                delimiter,
                given.headerRow(),
                dateFormat,
                decimal,
                grouping,
                columns,
                given.directionIn(),
                given.directionOut());
    }

    /** A column index from a JSON object key: ASCII digits only, inside the column cap. */
    private static int columnIndex(String key, String prefix) {
        if (key == null || !key.matches("^[0-9]{1,2}$")) {
            throw invalid(prefix + "columns", "keys are column indexes");
        }
        int index = Integer.parseInt(key);
        if (index >= 64) {
            throw invalid(prefix + "columns", "a file has at most 64 columns");
        }
        return index;
    }

    /**
     * @throws ImportRefusal {@code import-encoding-unsupported} for anything outside the allowlist
     *     — UTF-7 included, whatever the caller calls it
     */
    private static String charset(String chosen, String prefix) {
        if (chosen == null) {
            return null;
        }
        return ImportCharsets.canonicalName(chosen)
                .orElseThrow(
                        () ->
                                ImportRefusal.of(
                                        ErrorCode.IMPORT_ENCODING_UNSUPPORTED, prefix + "charset"));
    }

    private static ImportDateFormat dateFormat(String pattern, String prefix) {
        if (pattern == null) {
            return null;
        }
        return ImportDateFormat.ofPattern(pattern)
                .orElseThrow(
                        () -> invalid(prefix + "dateFormat", "must be one of the listed formats"));
    }

    private static Character single(String text, String field) {
        if (text == null) {
            return null;
        }
        if (text.length() != 1) {
            throw invalid(field, "must be a single character");
        }
        return text.charAt(0);
    }

    private static void refuseIfPresent(Object value, String field) {
        if (value != null) {
            throw invalid(field, "does not apply to this format");
        }
    }

    static InvalidRequestException invalid(String field, String detail) {
        return new InvalidRequestException(
                ErrorCode.VALIDATION_FAILED, detail, Map.of("field", field));
    }
}
