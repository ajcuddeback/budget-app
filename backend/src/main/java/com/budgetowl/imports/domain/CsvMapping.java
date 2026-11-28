package com.budgetowl.imports.domain;

import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.InvalidRequestException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * How to read one bank's CSV: the person's mapping, validated as a whole.
 *
 * <p>Bean Validation checks each field's shape at the edge; this checks what only the combination
 * can say — exactly one date column, an amount configuration that makes sense, a column with at
 * most one role — and, once the file is read, that every mapped column exists in it.
 *
 * @param delimiter one of {@code , ; \t |}
 * @param headerRow 1-based record holding the column labels, or 0 for none. Up to 20 records of
 *     preamble may come before it
 * @param dateFormat the chosen format, or {@code null} to detect it — accepted only when exactly
 *     one allowed format reads every row, and asked for otherwise
 * @param columns column index to role
 * @param directionIn with a {@link CsvColumnRole#DIRECTION} column, the value meaning money in
 * @param directionOut likewise, money out
 */
public record CsvMapping(
        char delimiter,
        int headerRow,
        ImportDateFormat dateFormat,
        char decimalSeparator,
        Character groupingSeparator,
        Map<Integer, CsvColumnRole> columns,
        String directionIn,
        String directionOut) {

    public static final Set<Character> DELIMITERS = Set.of(',', ';', '\t', '|');
    public static final Set<Character> DECIMAL_SEPARATORS = Set.of('.', ',');
    public static final Set<Character> GROUPING_SEPARATORS = Set.of(',', '.', ' ', ' ', ' ', '\'');

    /** Records of preamble a header may sit below (rule 3). */
    public static final int MAXIMUM_HEADER_ROW = 20;

    private static final String COLUMNS = "options.columns";

    public CsvMapping {
        columns = Map.copyOf(new TreeMap<>(Objects.requireNonNull(columns, "columns")));
        if (!DELIMITERS.contains(delimiter)) {
            throw invalid("options.delimiter", "must be one of , ; tab |");
        }
        if (headerRow < 0 || headerRow > MAXIMUM_HEADER_ROW) {
            throw invalid("options.headerRow", "must be between 0 and 20");
        }
        if (!DECIMAL_SEPARATORS.contains(decimalSeparator)) {
            throw invalid("options.decimalSeparator", "must be . or ,");
        }
        if (groupingSeparator != null
                && (!GROUPING_SEPARATORS.contains(groupingSeparator)
                        || groupingSeparator == decimalSeparator)) {
            throw invalid("options.groupingSeparator", "must differ from the decimal separator");
        }
        checkRoles(columns, directionIn, directionOut);
    }

    /** The lexer style these settings describe. */
    public ImportAmountLexer.Style amountStyle() {
        return ImportAmountLexer.Style.of(decimalSeparator, groupingSeparator);
    }

    /** The highest column any role names, so a row with fewer cells can be refused. */
    public int highestColumn() {
        return columns.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    public Integer columnOf(CsvColumnRole role) {
        for (Map.Entry<Integer, CsvColumnRole> entry : columns.entrySet()) {
            if (entry.getValue() == role) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Every mapped column must exist in the file.
     *
     * @throws InvalidRequestException {@code validation-failed} naming {@code options.columns}
     */
    public void requireColumnsWithin(int columnCount) {
        if (highestColumn() >= columnCount) {
            throw invalid(COLUMNS, "names a column the file does not have");
        }
    }

    /** The same mapping with a date format chosen. */
    public CsvMapping withDateFormat(ImportDateFormat chosen) {
        return new CsvMapping(
                delimiter,
                headerRow,
                chosen,
                decimalSeparator,
                groupingSeparator,
                columns,
                directionIn,
                directionOut);
    }

    private static void checkRoles(
            Map<Integer, CsvColumnRole> columns, String directionIn, String directionOut) {
        Map<CsvColumnRole, Integer> counts = new EnumMap<>(CsvColumnRole.class);
        for (Map.Entry<Integer, CsvColumnRole> entry : columns.entrySet()) {
            if (entry.getKey() == null || entry.getKey() < 0 || entry.getValue() == null) {
                throw invalid(COLUMNS, "every key is a column index and every value a role");
            }
            counts.merge(entry.getValue(), 1, Integer::sum);
        }
        int date = counts.getOrDefault(CsvColumnRole.DATE, 0);
        int amount = counts.getOrDefault(CsvColumnRole.AMOUNT, 0);
        int debit = counts.getOrDefault(CsvColumnRole.DEBIT, 0);
        int credit = counts.getOrDefault(CsvColumnRole.CREDIT, 0);
        int direction = counts.getOrDefault(CsvColumnRole.DIRECTION, 0);
        if (date != 1) {
            throw invalid(COLUMNS, "exactly one column must be the date");
        }
        boolean signed = amount == 1 && debit == 0 && credit == 0 && direction == 0;
        boolean split = amount == 0 && debit == 1 && credit == 1 && direction == 0;
        boolean directed = amount == 1 && debit == 0 && credit == 0 && direction == 1;
        if (!signed && !split && !directed) {
            throw invalid(
                    COLUMNS, "map one amount, a debit and a credit, or an amount and a direction");
        }
        if (counts.getOrDefault(CsvColumnRole.PAYEE, 0) > 1
                || counts.getOrDefault(CsvColumnRole.NOTE, 0) > 2
                || counts.getOrDefault(CsvColumnRole.REFERENCE, 0) > 1
                || counts.getOrDefault(CsvColumnRole.CURRENCY, 0) > 1) {
            throw invalid(COLUMNS, "at most one payee, two notes, one reference and one currency");
        }
        if (directed) {
            if (isBlank(directionIn) || isBlank(directionOut)) {
                throw invalid("options.directionIn", "a direction column needs both values");
            }
            if (directionIn.strip().equalsIgnoreCase(directionOut.strip())) {
                throw invalid("options.directionOut", "must differ from the value for money in");
            }
        } else if (!isBlank(directionIn) || !isBlank(directionOut)) {
            throw invalid("options.directionIn", "only applies with a direction column");
        }
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static InvalidRequestException invalid(String field, String detail) {
        return new InvalidRequestException(
                ErrorCode.VALIDATION_FAILED, detail, Map.of("field", field));
    }

    /** Settings only — but nothing here needs printing. */
    @Override
    public String toString() {
        return "CsvMapping[columns=" + columns.size() + "]";
    }
}
