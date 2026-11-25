package com.budgetowl.common.csv;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Writes a CSV file that is safe to open in a spreadsheet. <b>The one CSV writer in the
 * application</b>: every export goes through it, so the formula-injection control exists once.
 *
 * <h2>What it guarantees</h2>
 *
 * <ul>
 *   <li><b>RFC 4180.</b> Every field is quoted, embedded quotes are doubled, and records end in
 *       CRLF. A newline inside a value stays inside its one quoted field.
 *   <li><b>UTF-8 with a byte-order mark.</b> Without one, Excel decodes the file as the system code
 *       page and every non-Latin category or payee name is mojibake (ADR-0023 wants those names to
 *       exist).
 *   <li><b>Formula injection is escaped on every {@link ColumnType#TEXT TEXT} cell.</b> A text cell
 *       whose first character is one of {@code = + - @}, a tab ({@code 0x09}), a carriage return
 *       ({@code 0x0D}), or the full-width forms {@code ＝ ＋ － ＠} (which some spreadsheets fold to
 *       ASCII) is prefixed with a single quote {@code '}, following OWASP's guidance. A spreadsheet
 *       then shows the text rather than evaluating it: {@code =HYPERLINK("https://…?"&B2)} in a
 *       category name, or from slice 12 a merchant descriptor in a bank file nobody in the
 *       household wrote, cannot send a household's figures anywhere with one click.
 *   <li><b>The decision is made by column type, never by looking at the value.</b> A category named
 *       {@code -5} is text and is escaped; an amount of {@code -5.0000} is an {@link
 *       ColumnType#AMOUNT AMOUNT} and is not, so it stays a number in the spreadsheet. Sniffing
 *       ("does this look like a number?") would let an attacker choose which treatment their text
 *       gets.
 *   <li><b>Typed cells are validated before they are written.</b> A typed column is exempt from
 *       escaping only because the server produced its value from a typed one. So every typed value
 *       must match its column's pattern, or the writer throws rather than writes. A bug that put a
 *       name into an amount column fails loudly instead of smuggling text past the escaper.
 * </ul>
 *
 * <h2>Using it</h2>
 *
 * <pre>{@code
 * CsvWriter csv = CsvWriter.withColumns(
 *         CsvWriter.Column.currency("currency"),
 *         CsvWriter.Column.text("category"),
 *         CsvWriter.Column.amount("spending"));
 * csv.row("GBP", "Groceries", "612.0000");
 * byte[] file = csv.toBytes();
 * }</pre>
 *
 * <p>A {@code null} value is written as an empty field, in any column: "no figure" (a {@code
 * change} against zero, say) is not a number and must not be written as one.
 *
 * <p><b>Header cells are column codes</b> ({@code currency}, {@code uncategorisedIn}), not prose,
 * so the server never chooses a language for the file (ADR-0023). They must match {@code
 * [a-z][A-Za-z0-9]*}, which no trigger character can begin.
 *
 * <p><b>It never logs and never prints a value.</b> {@link #toString()} reports the shape only, and
 * a validation failure names the column, not the value that failed — the value is a household's
 * figure or a name it chose.
 *
 * <p>Not thread-safe; build one per response.
 */
public final class CsvWriter {

    /** The leading characters a spreadsheet may treat as the start of a formula. */
    private static final String FORMULA_TRIGGERS = "=+-@\t\r＝＋－＠";

    private static final byte[] BYTE_ORDER_MARK = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final Pattern HEADER_CODE = Pattern.compile("[a-z][A-Za-z0-9]*");

    private static final String RECORD_SEPARATOR = "\r\n";

    /**
     * What a column holds, which decides how its cells are written.
     *
     * <p>Only {@link #TEXT} is escaped. Every other type is exempt <em>because</em> it is
     * validated: a value that does not match the pattern is refused, so nothing that could begin
     * with a trigger character reaches a spreadsheet unescaped — except a minus sign on a decimal,
     * which is the point.
     */
    public enum ColumnType {
        /** Anything a person or a file supplied. Always escaped. */
        TEXT(null),
        /** A signed amount at scale 4, as it crosses the wire (ADR-0006): {@code -1234.5000}. */
        AMOUNT("-?\\d+\\.\\d{4}"),
        /** A signed ratio at scale 4: {@code 0.1602}, {@code -1.0000}. Same shape as an amount. */
        RATIO("-?\\d+\\.\\d{4}"),
        /** A calendar month: {@code 2026-08}. */
        PERIOD("\\d{4}-\\d{2}"),
        /** An ISO 4217 code: {@code GBP}. */
        CURRENCY("[A-Z]{3}"),
        /** A non-negative whole number: a count of rows. */
        COUNT("\\d+"),
        /** {@code true} or {@code false}. */
        BOOLEAN("true|false"),
        /** A stable code the server chose: {@code CATEGORY}, {@code UNCATEGORISED}. */
        CODE("[A-Z][A-Z0-9_]*");

        private final Pattern pattern;

        ColumnType(String regex) {
            this.pattern = regex == null ? null : Pattern.compile(regex);
        }

        boolean isEscaped() {
            return pattern == null;
        }

        boolean accepts(String value) {
            return pattern == null || pattern.matcher(value).matches();
        }
    }

    /**
     * One column: its header code and what it holds.
     *
     * @param header a column code, {@code [a-z][A-Za-z0-9]*}
     */
    public record Column(String header, ColumnType type) {

        public Column {
            Objects.requireNonNull(header, "header");
            Objects.requireNonNull(type, "type");
            if (!HEADER_CODE.matcher(header).matches()) {
                throw new IllegalArgumentException(
                        "a CSV header is a column code matching [a-z][A-Za-z0-9]*");
            }
        }

        public static Column text(String header) {
            return new Column(header, ColumnType.TEXT);
        }

        public static Column amount(String header) {
            return new Column(header, ColumnType.AMOUNT);
        }

        public static Column ratio(String header) {
            return new Column(header, ColumnType.RATIO);
        }

        public static Column period(String header) {
            return new Column(header, ColumnType.PERIOD);
        }

        public static Column currency(String header) {
            return new Column(header, ColumnType.CURRENCY);
        }

        public static Column count(String header) {
            return new Column(header, ColumnType.COUNT);
        }

        public static Column bool(String header) {
            return new Column(header, ColumnType.BOOLEAN);
        }

        public static Column code(String header) {
            return new Column(header, ColumnType.CODE);
        }
    }

    private final List<Column> columns;
    private final StringBuilder body = new StringBuilder();
    private int rows;

    private CsvWriter(List<Column> columns) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("a CSV file needs at least one column");
        }
        this.columns = List.copyOf(columns);
        List<String> headers = new ArrayList<>();
        for (Column column : this.columns) {
            headers.add(quote(column.header()));
        }
        body.append(String.join(",", headers)).append(RECORD_SEPARATOR);
    }

    /** A writer whose first record is these columns' header codes. */
    public static CsvWriter withColumns(Column... columns) {
        return new CsvWriter(Arrays.asList(columns));
    }

    /** A writer whose first record is these columns' header codes. */
    public static CsvWriter withColumns(List<Column> columns) {
        return new CsvWriter(columns);
    }

    /**
     * Appends one record.
     *
     * @param values one per column, in column order; {@code null} for an empty field
     * @throws IllegalArgumentException if the count is wrong, or a typed value does not match its
     *     column's pattern. The message names the column and never the value
     */
    public CsvWriter row(String... values) {
        return row(Arrays.asList(values));
    }

    /** See {@link #row(String...)}. The list may contain {@code null}. */
    public CsvWriter row(List<String> values) {
        Objects.requireNonNull(values, "values");
        if (values.size() != columns.size()) {
            throw new IllegalArgumentException(
                    "a CSV record needs "
                            + columns.size()
                            + " fields and was given "
                            + values.size());
        }
        List<String> fields = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            fields.add(field(columns.get(i), values.get(i)));
        }
        body.append(String.join(",", fields)).append(RECORD_SEPARATOR);
        rows++;
        return this;
    }

    /** The file: a UTF-8 byte-order mark, the header record, then every record appended. */
    public byte[] toBytes() {
        byte[] text = body.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(BYTE_ORDER_MARK.length + text.length);
        out.writeBytes(BYTE_ORDER_MARK);
        out.writeBytes(text);
        return out.toByteArray();
    }

    /**
     * The formula-injection escape alone, without quoting: {@code value} with a leading {@code '}
     * when it begins with a trigger character, and unchanged otherwise.
     *
     * <p>Public so that a later export with its own framing can apply exactly the same rule rather
     * than a second copy of the list. Apply it to <b>text</b> only — see the class note on why the
     * decision belongs to the column type.
     */
    public static String escapeFormula(String value) {
        Objects.requireNonNull(value, "value");
        if (!value.isEmpty() && FORMULA_TRIGGERS.indexOf(value.charAt(0)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    private static String field(Column column, String value) {
        if (value == null) {
            return quote("");
        }
        if (column.type().isEscaped()) {
            return quote(escapeFormula(value));
        }
        if (!column.type().accepts(value)) {
            // The column, never the value: the value is a figure or a name, and this message is
            // headed for a log.
            throw new IllegalArgumentException(
                    "CSV column '"
                            + column.header()
                            + "' is typed "
                            + column.type()
                            + " and was given a value that does not match it");
        }
        return quote(value);
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /** The shape only. Never a value. */
    @Override
    public String toString() {
        return "CsvWriter[columns=" + columns.size() + ", rows=" + rows + "]";
    }
}
