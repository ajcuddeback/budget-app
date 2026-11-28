package com.budgetowl.imports.domain;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The date formats a CSV or QIF file may be read with — an allowlist, never a pattern the person
 * types (docs/features/file-import.md rule 6).
 *
 * <p>Each carries its pattern, which is the value the API and {@code import_mappings.date_format}
 * use, and a <b>shape</b>: a regex over ASCII digits and the separators only. The shape is checked
 * before any formatter sees the text, so Arabic-Indic or full-width digits, a sign ({@code
 * +10000-01-01}) or a stray letter never reach {@code java.time} at all. Month names are not
 * accepted in v1: they depend on the language (ADR-0023).
 *
 * <p>The patterns use {@code uuuu}, never {@code yyyy}. Under {@code ResolverStyle.STRICT}, {@code
 * yyyy} is year-of-era and needs an era, so it refuses every date; the default {@code SMART}
 * resolver reads {@code 2026-02-30} as the 28th. Both verified on this repository's JDK.
 *
 * <p>Enum order is preference order: when several formats read every row to the same dates, the
 * first is the one reported.
 */
public enum ImportDateFormat {
    ISO("uuuu-MM-dd", "^[0-9]{4}-[0-9]{2}-[0-9]{2}$"),
    DAY_MONTH_YEAR("dd/MM/uuuu", "^[0-9]{2}/[0-9]{2}/[0-9]{4}$"),
    MONTH_DAY_YEAR("MM/dd/uuuu", "^[0-9]{2}/[0-9]{2}/[0-9]{4}$"),
    DAY_MONTH_YEAR_SHORT("d/M/uuuu", "^[0-9]{1,2}/[0-9]{1,2}/[0-9]{4}$"),
    MONTH_DAY_YEAR_SHORT("M/d/uuuu", "^[0-9]{1,2}/[0-9]{1,2}/[0-9]{4}$"),
    DAY_MONTH_YEAR_DOTTED("dd.MM.uuuu", "^[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}$"),
    DAY_MONTH_YEAR_DASHED("dd-MM-uuuu", "^[0-9]{2}-[0-9]{2}-[0-9]{4}$"),
    COMPACT("uuuuMMdd", "^[0-9]{8}$"),
    /** Quicken's older form. */
    QIF_MONTH_DAY_YEAR("M/d/yy", "^[0-9]{1,2}/[0-9]{1,2}/[0-9]{2}$"),
    /**
     * Quicken's form for years from 2000, {@code 8/3'26}. Quicken writes {@code /} before the year
     * for earlier years in the same file, so both are accepted here and read the same way.
     */
    QIF_MONTH_DAY_APOSTROPHE_YEAR("M/d'yy", "^[0-9]{1,2}/[0-9]{1,2}['/][0-9]{2}$"),
    QIF_DAY_MONTH_YEAR("d/M/yy", "^[0-9]{1,2}/[0-9]{1,2}/[0-9]{2}$");

    private final String pattern;
    private final Pattern shape;

    ImportDateFormat(String pattern, String shape) {
        this.pattern = pattern;
        this.shape = Pattern.compile(shape);
    }

    /** The pattern, which is also this format's name on the wire and in the database. */
    public String pattern() {
        return pattern;
    }

    boolean hasShapeOf(String text) {
        return shape.matcher(text).matches();
    }

    /** Whether the year is two digits, and so resolved by the 100-year window. */
    boolean twoDigitYear() {
        return pattern.endsWith("yy") && !pattern.endsWith("uuuu");
    }

    /** The format with this exact pattern. Case matters: {@code mm} is not {@code MM}. */
    public static Optional<ImportDateFormat> ofPattern(String pattern) {
        return Arrays.stream(values()).filter(format -> format.pattern.equals(pattern)).findFirst();
    }
}
