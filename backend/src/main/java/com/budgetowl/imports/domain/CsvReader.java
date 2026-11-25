package com.budgetowl.imports.domain;

import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.InvalidRequestException;
import com.budgetowl.imports.domain.ImportAmountLexer.Direction;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a CSV statement: the records, what to show the person about them, and — once they have said
 * which column is which — the rows (docs/features/file-import.md rule 5, "CSV").
 *
 * <p>The mapping is the person's, validated as a whole by {@link CsvMapping} and then again against
 * the file here. A row with fewer cells than the highest mapped column is a row problem; extra
 * cells are ignored.
 */
final class CsvReader {

    /** Records sampled for suggestions (rule 5). */
    static final int SAMPLE = 50;

    /** Data records shown in the mapping step. */
    static final int SHOWN = 5;

    /** Characters of each shown cell or label. */
    static final int CELL_SHOWN = 64;

    private final ImportLimits limits;
    private final ParseDeadline deadline;
    private final ImportDateParser dates;

    CsvReader(ImportLimits limits, ParseDeadline deadline, ImportDateParser dates) {
        this.limits = limits;
        this.deadline = deadline;
        this.dates = dates;
    }

    /** The outcome: what to show, and the rows when there was a mapping to read them with. */
    record Result(CsvShape shape, RawStatement statement) {}

    /**
     * @param mapping the person's mapping, or {@code null} to only describe the file
     * @param delimiterChosen the delimiter to read with when there is no mapping yet, or {@code
     *     null} to use the suggested one
     * @param headerRowChosen likewise, for the header row
     */
    Result read(
            String text, CsvMapping mapping, Character delimiterChosen, Integer headerRowChosen) {
        CsvSuggestion suggested = suggest(text);
        char delimiter =
                mapping != null
                        ? mapping.delimiter()
                        : delimiterChosen != null ? delimiterChosen : suggested.delimiter();
        int headerRow =
                mapping != null
                        ? mapping.headerRow()
                        : headerRowChosen != null ? headerRowChosen : suggested.headerRow();

        List<List<String>> records =
                CsvRecords.read(
                        text,
                        delimiter,
                        limits,
                        limits.maximumRows() + CsvMapping.MAXIMUM_HEADER_ROW,
                        deadline);
        if (headerRow > records.size()) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "the header row is past the end of the file",
                    Map.of("field", "options.headerRow"));
        }
        List<List<String>> data = records.subList(headerRow, records.size());
        if (data.size() > limits.maximumRows()) {
            throw ImportRefusal.of(ErrorCode.IMPORT_TOO_MANY_ROWS);
        }
        List<String> header = headerRow == 0 ? List.of() : records.get(headerRow - 1);
        int columnCount =
                !header.isEmpty() ? header.size() : data.isEmpty() ? 0 : data.get(0).size();

        CsvShape shape =
                new CsvShape(
                        columnCount,
                        shown(header),
                        data.subList(0, Math.min(SHOWN, data.size())).stream()
                                .map(CsvReader::shown)
                                .toList(),
                        suggested);
        if (mapping == null) {
            return new Result(shape, null);
        }
        mapping.requireColumnsWithin(Math.max(columnCount, 1));

        List<RawRow> rows = new ArrayList<>(data.size());
        for (int index = 0; index < data.size(); index++) {
            int record = headerRow + index + 1;
            deadline.check(index + 1);
            rows.add(row(record, data.get(index), mapping));
        }
        return new Result(shape, new RawStatement(rows, null, null, null));
    }

    private static RawRow row(int record, List<String> cells, CsvMapping mapping) {
        if (cells.size() <= mapping.highestColumn()) {
            return RawRow.unreadable(
                    record, RowProblem.COLUMNS_SHORT, mapping.highestColumn(), null);
        }
        Integer dateColumn = mapping.columnOf(CsvColumnRole.DATE);
        Integer amountColumn = mapping.columnOf(CsvColumnRole.AMOUNT);
        String amount;
        Direction direction = Direction.NONE;

        if (amountColumn != null) {
            amount = cells.get(amountColumn);
            Integer directionColumn = mapping.columnOf(CsvColumnRole.DIRECTION);
            if (directionColumn != null) {
                String said = cells.get(directionColumn).strip();
                if (said.equalsIgnoreCase(mapping.directionIn().strip())) {
                    direction = Direction.IN;
                } else if (said.equalsIgnoreCase(mapping.directionOut().strip())) {
                    direction = Direction.OUT;
                } else {
                    return RawRow.unreadable(
                            record, RowProblem.AMOUNT_UNREADABLE, directionColumn, said);
                }
            }
        } else {
            int debitColumn = mapping.columnOf(CsvColumnRole.DEBIT);
            int creditColumn = mapping.columnOf(CsvColumnRole.CREDIT);
            String debit = cells.get(debitColumn);
            String credit = cells.get(creditColumn);
            boolean hasDebit = !debit.isBlank();
            boolean hasCredit = !credit.isBlank();
            if (hasDebit == hasCredit) {
                // Exactly one of the two, per row. Both empty is a missing amount; both filled is
                // two answers to one question.
                return RawRow.unreadable(
                        record,
                        hasDebit ? RowProblem.AMOUNT_UNREADABLE : RowProblem.MISSING_FIELD,
                        debitColumn,
                        null);
            }
            amount = hasDebit ? debit : credit;
            direction = hasDebit ? Direction.OUT : Direction.IN;
            amountColumn = hasDebit ? debitColumn : creditColumn;
        }

        List<String> notes = new ArrayList<>(2);
        for (Map.Entry<Integer, CsvColumnRole> entry : mapping.columns().entrySet()) {
            if (entry.getValue() == CsvColumnRole.NOTE && !cells.get(entry.getKey()).isBlank()) {
                notes.add(cells.get(entry.getKey()));
            }
        }
        return new RawRow(
                record,
                cells.get(dateColumn),
                amount,
                null,
                direction,
                cellOf(cells, mapping.columnOf(CsvColumnRole.PAYEE)),
                notes.isEmpty() ? null : String.join(" · ", notes),
                cellOf(cells, mapping.columnOf(CsvColumnRole.REFERENCE)),
                cellOf(cells, mapping.columnOf(CsvColumnRole.CURRENCY)),
                null,
                null,
                null,
                dateColumn,
                amountColumn);
    }

    private static String cellOf(List<String> cells, Integer column) {
        return column == null ? null : cells.get(column);
    }

    private static List<String> shown(List<String> cells) {
        return cells.stream()
                .map(cell -> ImportText.cut(ImportText.normalise(cell), CELL_SHOWN))
                .toList();
    }

    // ----------------------------------------------------------------------------- suggestions

    /**
     * What the server would choose, from the first {@link #SAMPLE} records. Bounded: it reads at
     * most that many records per candidate delimiter, from a text the pre-scan already bounded.
     */
    CsvSuggestion suggest(String text) {
        String head = firstLines(text, SAMPLE + CsvMapping.MAXIMUM_HEADER_ROW);
        char delimiter = ',';
        int bestScore = -1;
        List<List<String>> bestRecords = List.of();
        for (char candidate : new char[] {',', ';', '\t', '|'}) {
            List<List<String>> sample;
            try {
                sample = CsvRecords.read(head, candidate, limits, Integer.MAX_VALUE, deadline);
            } catch (ImportRefusal unreadable) {
                // A delimiter that cannot read the sample is simply not the suggestion. The real
                // read, with whichever delimiter the person confirms, refuses the file properly.
                continue;
            }
            int score = consistency(sample);
            if (score > bestScore) {
                bestScore = score;
                delimiter = candidate;
                bestRecords = sample;
            }
        }

        int headerRow = headerRowOf(bestRecords);
        List<List<String>> data =
                bestRecords.subList(Math.min(headerRow, bestRecords.size()), bestRecords.size());
        List<String> labels = headerRow == 0 ? List.of() : bestRecords.get(headerRow - 1);

        Map<Integer, CsvColumnRole> roles = new LinkedHashMap<>();
        Integer dateColumn = dateColumn(data);
        ImportDateFormat dateFormat = null;
        List<ImportDateFormat> candidates = List.of();
        if (dateColumn != null) {
            roles.put(dateColumn, CsvColumnRole.DATE);
            List<ImportDateFormat> readings = dates.readingsOfAll(column(data, dateColumn), false);
            if (readings.size() == 1) {
                dateFormat = readings.get(0);
            } else {
                candidates = readings;
            }
        }
        rolesFromLabels(labels, roles);

        char decimal = '.';
        Character grouping = null;
        Integer amountColumn = null;
        for (Map.Entry<Integer, CsvColumnRole> entry : roles.entrySet()) {
            if (entry.getValue() == CsvColumnRole.AMOUNT
                    || entry.getValue() == CsvColumnRole.DEBIT) {
                amountColumn = entry.getKey();
                break;
            }
        }
        if (amountColumn != null) {
            for (String cell : column(data, amountColumn)) {
                String trimmed = cell.strip();
                if (trimmed.matches("^[-+(]?[0-9]{1,3}(\\.[0-9]{3})+,[0-9]{1,4}\\)?$")) {
                    decimal = ',';
                    grouping = '.';
                    break;
                }
                if (trimmed.matches("^[-+(]?[0-9]+,[0-9]{1,2}\\)?$")) {
                    decimal = ',';
                    break;
                }
                if (trimmed.matches("^[-+(]?[0-9]{1,3}(,[0-9]{3})+(\\.[0-9]{1,4})?\\)?$")) {
                    grouping = ',';
                    break;
                }
            }
        }
        return new CsvSuggestion(
                delimiter, headerRow, dateFormat, candidates, decimal, grouping, roles);
    }

    /** How many sampled records share the commonest field count, if that count is above one. */
    private static int consistency(List<List<String>> records) {
        Map<Integer, Integer> byWidth = new LinkedHashMap<>();
        for (List<String> record : records) {
            byWidth.merge(record.size(), 1, Integer::sum);
        }
        int best = 0;
        for (Map.Entry<Integer, Integer> entry : byWidth.entrySet()) {
            if (entry.getKey() > 1 && entry.getValue() > best) {
                best = entry.getValue();
            }
        }
        return best;
    }

    /**
     * The 1-based record holding the labels: the first one, within the preamble limit, that reads
     * as no date anywhere and is followed by a record of the same width that does. Zero for none.
     */
    private int headerRowOf(List<List<String>> records) {
        int limit = Math.min(CsvMapping.MAXIMUM_HEADER_ROW, records.size() - 1);
        for (int index = 0; index < limit; index++) {
            List<String> candidate = records.get(index);
            List<String> next = records.get(index + 1);
            if (candidate.size() > 1
                    && candidate.size() == next.size()
                    && !anyDate(candidate)
                    && anyDate(next)) {
                return index + 1;
            }
        }
        return 0;
    }

    private boolean anyDate(List<String> cells) {
        for (String cell : cells) {
            if (!dates.readingsOfAll(List.of(cell), false).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** The first column in which every sampled cell is a date in some allowed format. */
    private Integer dateColumn(List<List<String>> data) {
        if (data.isEmpty()) {
            return null;
        }
        int width = data.get(0).size();
        for (int column = 0; column < width; column++) {
            List<String> cells = column(data, column);
            if (!cells.isEmpty() && !dates.readingsOfAll(cells, false).isEmpty()) {
                return column;
            }
        }
        return null;
    }

    private static List<String> column(List<List<String>> data, int column) {
        List<String> cells = new ArrayList<>(data.size());
        for (List<String> record : data) {
            if (column < record.size() && !record.get(column).isBlank()) {
                cells.add(record.get(column));
            }
        }
        return cells;
    }

    /** English header words only, and only as a starting point the person corrects. */
    private static void rolesFromLabels(List<String> labels, Map<Integer, CsvColumnRole> roles) {
        Map<CsvColumnRole, Integer> taken = new EnumMap<>(CsvColumnRole.class);
        for (CsvColumnRole role : roles.values()) {
            taken.merge(role, 1, Integer::sum);
        }
        for (int column = 0; column < labels.size(); column++) {
            if (roles.containsKey(column)) {
                continue;
            }
            String label = labels.get(column).strip().toLowerCase(Locale.ROOT);
            CsvColumnRole role = roleOf(label);
            if (role == null) {
                continue;
            }
            int limit = role == CsvColumnRole.NOTE ? 2 : 1;
            if (taken.getOrDefault(role, 0) < limit) {
                roles.put(column, role);
                taken.merge(role, 1, Integer::sum);
            }
        }
        boolean debitAndCredit =
                taken.containsKey(CsvColumnRole.DEBIT) && taken.containsKey(CsvColumnRole.CREDIT);
        if (debitAndCredit && taken.containsKey(CsvColumnRole.AMOUNT)) {
            roles.values().removeIf(role -> role == CsvColumnRole.AMOUNT);
        }
        if (!debitAndCredit) {
            roles.values()
                    .removeIf(role -> role == CsvColumnRole.DEBIT || role == CsvColumnRole.CREDIT);
        }
    }

    private static CsvColumnRole roleOf(String label) {
        if (label.contains("debit")
                || label.contains("withdrawal")
                || label.contains("paid out")
                || label.contains("money out")) {
            return CsvColumnRole.DEBIT;
        }
        if (label.contains("credit")
                || label.contains("deposit")
                || label.contains("paid in")
                || label.contains("money in")) {
            return CsvColumnRole.CREDIT;
        }
        if (label.contains("amount") || label.equals("value")) {
            return CsvColumnRole.AMOUNT;
        }
        if (label.contains("payee")
                || label.contains("description")
                || label.contains("merchant")
                || label.equals("name")
                || label.contains("details")
                || label.contains("narrative")) {
            return CsvColumnRole.PAYEE;
        }
        if (label.contains("memo") || label.contains("note")) {
            return CsvColumnRole.NOTE;
        }
        if (label.contains("transaction id") || label.equals("id") || label.contains("fitid")) {
            return CsvColumnRole.REFERENCE;
        }
        if (label.equals("currency")) {
            return CsvColumnRole.CURRENCY;
        }
        return null;
    }

    private static String firstLines(String text, int lines) {
        int seen = 0;
        int index = 0;
        while (index < text.length() && seen < lines) {
            char character = text.charAt(index);
            if (character == '\n' || character == '\r') {
                seen++;
            }
            index++;
        }
        return text.substring(0, index);
    }
}
