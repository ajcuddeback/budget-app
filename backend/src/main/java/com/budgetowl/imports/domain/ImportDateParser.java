package com.budgetowl.imports.domain;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads a date from a file as a calendar date, strictly (docs/features/file-import.md rule 6).
 *
 * <p>The stored value is a {@code date}, so no time zone is ever applied. For OFX's {@code
 * DTPOSTED}, which looks like {@code 20260731233000.000[-8:PST]}, the first eight characters are
 * the date and the rest is checked for shape and then ignored: converting it to an instant and back
 * would move a transaction near midnight into the neighbouring day, and sometimes the neighbouring
 * month.
 *
 * <p>Every result is bounded: from 1900-01-01 to today plus 31 days, today being read from the
 * injected {@link Clock}. The upper margin allows for pending rows a bank dates ahead and for the
 * server and the person being in different zones.
 *
 * <p>One instance per parse: the two-digit-year window and the range are fixed from the clock when
 * it is made, so every row in a file is judged against the same day.
 */
public final class ImportDateParser {

    public static final LocalDate EARLIEST = LocalDate.of(1900, 1, 1);

    /** Days after today a row may be dated (rule 6, "Range"). */
    public static final int DAYS_AHEAD = 31;

    /**
     * {@code YYYYMMDD}, then optionally {@code HHMM}, {@code SS}, {@code .XXX}, and a {@code
     * [offset:TZ]} suffix. Only the date digits are used.
     */
    private static final Pattern OFX_DATE =
            Pattern.compile(
                    "^[0-9]{8}([0-9]{4}([0-9]{2}(\\.[0-9]{1,3})?)?)?"
                            + "(\\[[+-]?[0-9]{1,2}(\\.[0-9]{1,2})?(:[A-Za-z]{1,10})?\\])?$");

    private final LocalDate latest;
    private final Map<ImportDateFormat, DateTimeFormatter> formatters =
            new EnumMap<>(ImportDateFormat.class);

    public ImportDateParser(Clock clock) {
        LocalDate today = LocalDate.now(Objects.requireNonNull(clock, "clock"));
        this.latest = today.plusDays(DAYS_AHEAD);
        // The 100-year window ending at next year: in 2026, "27" is 2027 and "28" is 1928.
        int windowStart = today.getYear() + 1 - 99;
        for (ImportDateFormat format : ImportDateFormat.values()) {
            formatters.put(format, formatterFor(format, windowStart));
        }
    }

    /** The outcome for one cell: a date, or the reason there is none. */
    public sealed interface Parsed permits Parsed.Day, Parsed.Refused {

        record Day(LocalDate date) implements Parsed {}

        record Refused(RowProblem problem) implements Parsed {}
    }

    /**
     * @param text the cell as decoded; QIF's space-padded {@code 8/ 3'26} is handled by {@link
     *     #parseQif}
     */
    public Parsed parse(String text, ImportDateFormat format) {
        Objects.requireNonNull(format, "format");
        Optional<LocalDate> read = read(text, format);
        if (read.isEmpty()) {
            return new Parsed.Refused(
                    text == null || text.isBlank()
                            ? RowProblem.MISSING_FIELD
                            : RowProblem.DATE_UNREADABLE);
        }
        return inRange(read.get());
    }

    /** QIF dates, which Quicken pads with spaces inside: {@code 8/ 3'26}. */
    public Parsed parseQif(String text, ImportDateFormat format) {
        return parse(text == null ? null : text.replace(" ", ""), format);
    }

    /** OFX {@code DTPOSTED}: the date digits only, after the whole value has been shape-checked. */
    public Parsed parseOfx(String text) {
        if (text == null || text.isBlank()) {
            return new Parsed.Refused(RowProblem.MISSING_FIELD);
        }
        String trimmed = text.strip();
        if (trimmed.length() > ImportAmountLexer.MAXIMUM_LENGTH
                || !OFX_DATE.matcher(trimmed).matches()) {
            return new Parsed.Refused(RowProblem.DATE_UNREADABLE);
        }
        return parse(trimmed.substring(0, 8), ImportDateFormat.COMPACT);
    }

    /**
     * The formats that read <b>every</b> one of these cells, grouped by what they read them as.
     *
     * <p>{@code dd/MM/uuuu} and {@code d/M/uuuu} read {@code 03/08/2026} identically, so they are
     * not a choice anybody needs to make; {@code dd/MM/uuuu} and {@code MM/dd/uuuu} read it as two
     * different days, and that is. The caller asks the person only when more than one distinct
     * reading survives (rule 6, "Ambiguity is the user's to resolve").
     *
     * @return each surviving reading's preferred format, in enum order; empty when no format reads
     *     them all
     */
    public List<ImportDateFormat> readingsOfAll(List<String> cells, boolean qif) {
        List<ImportDateFormat> distinct = new ArrayList<>();
        List<List<LocalDate>> seen = new ArrayList<>();
        for (ImportDateFormat format : ImportDateFormat.values()) {
            List<LocalDate> dates = new ArrayList<>(cells.size());
            boolean all = true;
            for (String cell : cells) {
                // The shape and the calendar, not the range: one row from 1899 is that row's
                // problem, and must not make an unambiguous file look ambiguous.
                Optional<LocalDate> read =
                        read(qif && cell != null ? cell.replace(" ", "") : cell, format);
                if (read.isEmpty()) {
                    all = false;
                    break;
                }
                dates.add(read.get());
            }
            if (all && !seen.contains(dates)) {
                seen.add(dates);
                distinct.add(format);
            }
        }
        return List.copyOf(distinct);
    }

    // ------------------------------------------------------------------------------- internals

    private Optional<LocalDate> read(String text, ImportDateFormat format) {
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = text.strip();
        if (trimmed.length() > ImportAmountLexer.MAXIMUM_LENGTH || !format.hasShapeOf(trimmed)) {
            return Optional.empty();
        }
        String forFormatter =
                format == ImportDateFormat.QIF_MONTH_DAY_APOSTROPHE_YEAR
                        ? trimmed.replace('\'', '/')
                        : trimmed;
        try {
            return Optional.of(LocalDate.from(formatters.get(format).parse(forFormatter)));
        } catch (DateTimeException refused) {
            // 30 February, month 13, day 0. The exception's message quotes the text, so it is
            // dropped here and the caller reports a fixed code.
            return Optional.empty();
        }
    }

    private Parsed inRange(LocalDate date) {
        if (date.isBefore(EARLIEST) || date.isAfter(latest)) {
            return new Parsed.Refused(RowProblem.DATE_OUT_OF_RANGE);
        }
        return new Parsed.Day(date);
    }

    private static DateTimeFormatter formatterFor(ImportDateFormat format, int windowStart) {
        if (!format.twoDigitYear()) {
            return DateTimeFormatter.ofPattern(format.pattern())
                    .withResolverStyle(ResolverStyle.STRICT);
        }
        // Built by hand rather than from "yy", which is year-of-era: appendValueReduced on YEAR
        // is what gives a two-digit year a window, and it needs no era under STRICT.
        boolean dayFirst = format == ImportDateFormat.QIF_DAY_MONTH_YEAR;
        DateTimeFormatterBuilder builder = new DateTimeFormatterBuilder();
        builder.appendValue(
                        dayFirst ? ChronoField.DAY_OF_MONTH : ChronoField.MONTH_OF_YEAR,
                        1,
                        2,
                        java.time.format.SignStyle.NOT_NEGATIVE)
                .appendLiteral('/')
                .appendValue(
                        dayFirst ? ChronoField.MONTH_OF_YEAR : ChronoField.DAY_OF_MONTH,
                        1,
                        2,
                        java.time.format.SignStyle.NOT_NEGATIVE)
                .appendLiteral('/')
                .appendValueReduced(ChronoField.YEAR, 2, 2, windowStart);
        return builder.toFormatter().withResolverStyle(ResolverStyle.STRICT);
    }
}
