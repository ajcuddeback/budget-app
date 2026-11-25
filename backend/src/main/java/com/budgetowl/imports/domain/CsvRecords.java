package com.budgetowl.imports.domain;

import com.budgetowl.common.ErrorCode;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits CSV text into records and fields, per RFC 4180 (docs/features/file-import.md rule 5).
 *
 * <p>A quoted field may contain the delimiter, a doubled {@code ""} and line breaks; {@code \r\n},
 * {@code \n} and a bare {@code \r} all end a record outside quotes. Blank lines are skipped.
 *
 * <p><b>Bounded throughout.</b> A record may not exceed the line-length cap in total, so an
 * unclosed quote on line 2 cannot swallow the rest of the file into one field: it runs into the cap
 * and is refused as {@code unclosed-quote}, as is a quote still open at the end of the file. A
 * record may have at most {@code csvMaximumColumns} fields. Text after a closing quote, other than
 * spaces, is refused too rather than guessed at.
 */
final class CsvRecords {

    private CsvRecords() {}

    /**
     * @param maximumRecords how many records may be read before the file is refused as too long
     */
    static List<List<String>> read(
            String text,
            char delimiter,
            ImportLimits limits,
            int maximumRecords,
            ParseDeadline deadline) {
        List<List<String>> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean afterQuote = false;
        boolean quoted = false;
        int recordLength = 0;
        int length = text.length();
        int index = 0;

        while (index < length) {
            char character = text.charAt(index);
            if (inQuotes) {
                if (character == '"') {
                    if (index + 1 < length && text.charAt(index + 1) == '"') {
                        field.append('"');
                        index += 2;
                    } else {
                        inQuotes = false;
                        afterQuote = true;
                        index++;
                    }
                    continue;
                }
                if (++recordLength > limits.maximumLineLength()) {
                    throw ImportRefusal.malformed(MalformedReason.UNCLOSED_QUOTE);
                }
                field.append(character);
                index++;
                continue;
            }

            if (character == delimiter || character == '\r' || character == '\n') {
                addField(fields, field, limits);
                afterQuote = false;
                quoted = false;
                if (character == delimiter) {
                    index++;
                    continue;
                }
                boolean crlf =
                        character == '\r' && index + 1 < length && text.charAt(index + 1) == '\n';
                index += crlf ? 2 : 1;
                if (!isBlankRecord(fields)) {
                    if (records.size() >= maximumRecords) {
                        throw ImportRefusal.of(ErrorCode.IMPORT_TOO_MANY_ROWS);
                    }
                    records.add(List.copyOf(fields));
                    deadline.check(records.size());
                }
                fields.clear();
                recordLength = 0;
                continue;
            }

            if (afterQuote) {
                if (character == ' ') {
                    index++;
                    continue;
                }
                throw ImportRefusal.malformed(MalformedReason.UNCLOSED_QUOTE);
            }
            if (character == '"' && !quoted && field.toString().isBlank()) {
                field.setLength(0);
                inQuotes = true;
                quoted = true;
                index++;
                continue;
            }
            if (++recordLength > limits.maximumLineLength()) {
                throw ImportRefusal.malformed(MalformedReason.LINE_TOO_LONG);
            }
            field.append(character);
            index++;
        }
        if (inQuotes) {
            throw ImportRefusal.malformed(MalformedReason.UNCLOSED_QUOTE);
        }
        if (field.length() > 0 || !fields.isEmpty() || quoted) {
            addField(fields, field, limits);
            if (!isBlankRecord(fields)) {
                if (records.size() >= maximumRecords) {
                    throw ImportRefusal.of(ErrorCode.IMPORT_TOO_MANY_ROWS);
                }
                records.add(List.copyOf(fields));
            }
        }
        return records;
    }

    private static void addField(List<String> fields, StringBuilder field, ImportLimits limits) {
        if (fields.size() >= limits.csvMaximumColumns()) {
            throw ImportRefusal.malformed(MalformedReason.TOO_MANY_COLUMNS);
        }
        fields.add(field.toString());
        field.setLength(0);
    }

    private static boolean isBlankRecord(List<String> fields) {
        return fields.size() == 1 && fields.get(0).isBlank();
    }
}
