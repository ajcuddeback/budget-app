package com.budgetowl.imports.domain;

import java.text.Normalizer;

/**
 * The one normaliser every stored text value from a file goes through (docs/features/file-import.md
 * rule 4, "After decoding").
 *
 * <p>In order: Unicode NFC, so "é" typed two ways is one payee; whitespace controls become spaces;
 * every other C0 and C1 control is removed, as are zero-width characters and the bidi embedding,
 * override and isolate controls; runs of whitespace collapse to one space; and the ends are
 * trimmed. LRM and RLM (U+200E, U+200F) are <b>kept</b>, because legitimate right-to-left payee
 * names use them (ADR-0023).
 *
 * <p>Why the bidi controls go: a payee wrapped in U+202E (RIGHT-TO-LEFT OVERRIDE) displays as a
 * different, trusted-looking name, or reverses the amount beside it. Why the control characters go:
 * a payee carrying CR/LF would forge a log line if it were ever logged, which it is not — this is
 * the second line of defence. A NUL anywhere refuses the whole file before this is reached.
 */
public final class ImportText {

    private ImportText() {}

    public static String normalise(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String composed = Normalizer.normalize(raw, Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder(composed.length());
        boolean pendingSpace = false;
        int index = 0;
        while (index < composed.length()) {
            int codePoint = composed.codePointAt(index);
            index += Character.charCount(codePoint);
            if (isSpace(codePoint)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (isRemoved(codePoint)) {
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.appendCodePoint(codePoint);
        }
        return out.toString();
    }

    /**
     * The longest prefix that fits {@code maximum} UTF-16 units without splitting a surrogate pair,
     * trimmed. That is never more code points than {@code maximum} either, so it fits both the
     * entity's own length check and the database's {@code length()}.
     */
    public static String cut(String text, int maximum) {
        if (text.length() <= maximum) {
            return text;
        }
        int end = maximum;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end).strip();
    }

    private static boolean isSpace(int codePoint) {
        return codePoint == '\t'
                || codePoint == '\n'
                || codePoint == '\r'
                || codePoint == '\u000B'
                || codePoint == '\f'
                || codePoint == 0x85
                || Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint);
    }

    private static boolean isRemoved(int codePoint) {
        boolean c0 = codePoint < 0x20;
        boolean c1 = codePoint >= 0x7F && codePoint <= 0x9F;
        boolean zeroWidth = (codePoint >= 0x200B && codePoint <= 0x200D) || codePoint == 0xFEFF;
        boolean bidiEmbeddingOrOverride = codePoint >= 0x202A && codePoint <= 0x202E;
        boolean bidiIsolate = codePoint >= 0x2066 && codePoint <= 0x2069;
        return c0 || c1 || zeroWidth || bidiEmbeddingOrOverride || bidiIsolate;
    }
}
