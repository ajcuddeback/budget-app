package com.budgetowl.imports.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Every bound the import path enforces (docs/features/file-import.md rule 3), in one place.
 *
 * <p><b>The order they are applied in is the point:</b> bytes are counted before anything decodes
 * them, lines are counted before anything parses them, and records are counted while parsing. A
 * file over any one of them is refused whole, never truncated — truncating silently drops a
 * household's money.
 *
 * @param maximumBytes the decoded file, after base64. Also enforced on the request body, sized for
 *     base64, before anything reads it
 * @param csvMaximumLines physical lines, counted before the CSV reader runs
 * @param qifMaximumLines physical lines for QIF, higher because one record is several lines
 * @param maximumLineLength characters per physical line, CSV and QIF only. Some banks write OFX 2.x
 *     as a single line, so OFX is bounded by its element caps instead
 * @param maximumRows transactions per file, every format
 * @param csvMaximumColumns cells per CSV record
 * @param ofxMaximumDepth element nesting
 * @param ofxMaximumElements elements per file
 * @param ofxMaximumNameLength characters in a tag name
 * @param ofxMaximumLeafLength characters of text in one element
 * @param parseDeadline wall-clock time a parse may take, checked every {@link #DEADLINE_STRIDE}
 *     records
 */
public record ImportLimits(
        int maximumBytes,
        int csvMaximumLines,
        int qifMaximumLines,
        int maximumLineLength,
        int maximumRows,
        int csvMaximumColumns,
        int ofxMaximumDepth,
        int ofxMaximumElements,
        int ofxMaximumNameLength,
        int ofxMaximumLeafLength,
        Duration parseDeadline) {

    /** How many records pass between deadline checks. */
    public static final int DEADLINE_STRIDE = 256;

    /** 5 MiB (decision 1). */
    public static final int FIVE_MIB = 5 * 1024 * 1024;

    /** The decided values (decision 1 and rule 3). */
    public static final ImportLimits DECIDED =
            new ImportLimits(
                    FIVE_MIB,
                    12_000,
                    200_000,
                    4096,
                    10_000,
                    64,
                    32,
                    200_000,
                    64,
                    1024,
                    Duration.ofSeconds(15));

    public ImportLimits {
        Objects.requireNonNull(parseDeadline, "parseDeadline");
        if (maximumBytes <= 0
                || csvMaximumLines <= 0
                || qifMaximumLines <= 0
                || maximumLineLength <= 0
                || maximumRows <= 0
                || csvMaximumColumns <= 0
                || ofxMaximumDepth <= 0
                || ofxMaximumElements <= 0
                || ofxMaximumNameLength <= 0
                || ofxMaximumLeafLength <= 0
                || parseDeadline.isNegative()
                || parseDeadline.isZero()) {
            throw new IllegalArgumentException("every import limit must be positive");
        }
    }

    /**
     * The largest request body that can carry a file at the cap: base64 grows it by a third, and
     * the JSON around it (the account id, the options, a digest and up to the row cap of overrides)
     * is bounded too.
     */
    public long maximumBodyBytes() {
        long base64 = ((long) maximumBytes + 2) / 3 * 4;
        long envelope = 64L * 1024 + (long) maximumRows * 32;
        return base64 + envelope;
    }
}
