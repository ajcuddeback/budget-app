package com.budgetowl.imports.domain;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The character sets a statement may be in: an allowlist, and no silent guessing
 * (docs/features/file-import.md rule 4).
 *
 * <p><b>This is never {@code Charset.forName(whateverTheFileSaid)}.</b> Every {@link Charset} here
 * is looked up from a constant in this class; a name from a file or a request is only ever compared
 * with those constants. UTF-7 is not on the list whatever a file declares — it encodes {@code <}
 * and {@code >} as innocent-looking ASCII and is the classic way to slip markup past a filter — and
 * neither are EBCDIC code pages or UTF-32.
 *
 * <p>Every decode is <b>strict</b>: {@link CodingErrorAction#REPORT}, never {@code REPLACE}. A byte
 * a single-byte charset does not define (0x81 in windows-1252) is a decode error, not U+FFFD, so a
 * wrong guess fails loudly instead of quietly corrupting a payee.
 */
public final class ImportCharsets {

    /** The allowlist, in the order a person is offered it. Names are the wire values. */
    private static final Map<String, Charset> ALLOWED;

    static {
        Map<String, Charset> allowed = new LinkedHashMap<>();
        allowed.put("UTF-8", StandardCharsets.UTF_8);
        allowed.put("UTF-16LE", StandardCharsets.UTF_16LE);
        allowed.put("UTF-16BE", StandardCharsets.UTF_16BE);
        allowed.put("windows-1252", Charset.forName("windows-1252"));
        allowed.put("windows-1250", Charset.forName("windows-1250"));
        allowed.put("windows-1251", Charset.forName("windows-1251"));
        allowed.put("ISO-8859-1", StandardCharsets.ISO_8859_1);
        allowed.put("ISO-8859-2", Charset.forName("ISO-8859-2"));
        allowed.put("ISO-8859-15", Charset.forName("ISO-8859-15"));
        ALLOWED = java.util.Collections.unmodifiableMap(allowed);
    }

    /**
     * The single-byte candidates offered with samples when nothing else decided (rule 4, step 4).
     */
    public static final List<String> SINGLE_BYTE_CANDIDATES =
            List.of(
                    "windows-1252",
                    "windows-1250",
                    "windows-1251",
                    "ISO-8859-1",
                    "ISO-8859-2",
                    "ISO-8859-15");

    /** Pre-selected when the person is asked, because it is the commonest bank-export fallback. */
    public static final String SUGGESTED_FALLBACK = "windows-1252";

    private ImportCharsets() {}

    /** Every allowed name, for a response that has to offer the choice. */
    public static List<String> allowedNames() {
        return List.copyOf(ALLOWED.keySet());
    }

    /**
     * The allowed charset with this name, compared case-insensitively. Anything else — UTF-7,
     * IBM037, a name the JDK would happily resolve — is empty.
     */
    public static Optional<String> canonicalName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String wanted = name.strip();
        for (String allowed : ALLOWED.keySet()) {
            if (allowed.equalsIgnoreCase(wanted)) {
                return Optional.of(allowed);
            }
        }
        return Optional.empty();
    }

    /** What a byte-order mark says, and how many bytes it takes. */
    public record Bom(String charset, int length) {}

    /**
     * The BOM at the start of these bytes, if there is one.
     *
     * @throws ImportRefusal {@code import-encoding-unsupported} for a UTF-32 BOM, which must be
     *     checked before UTF-16LE's because it begins with the same two bytes
     */
    public static Optional<Bom> bomOf(byte[] bytes) {
        if (startsWith(bytes, 0x00, 0x00, 0xFE, 0xFF)
                || startsWith(bytes, 0xFF, 0xFE, 0x00, 0x00)) {
            throw ImportRefusal.of(com.budgetowl.common.ErrorCode.IMPORT_ENCODING_UNSUPPORTED);
        }
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return Optional.of(new Bom("UTF-8", 3));
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return Optional.of(new Bom("UTF-16LE", 2));
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return Optional.of(new Bom("UTF-16BE", 2));
        }
        return Optional.empty();
    }

    /**
     * Decodes strictly, or not at all.
     *
     * @param allowedName a name from the allowlist — anything else is a programming error
     * @return empty when any byte does not decode
     */
    public static Optional<String> decode(byte[] bytes, int offset, String allowedName) {
        Charset charset = ALLOWED.get(allowedName);
        if (charset == null) {
            throw new IllegalArgumentException("not an allowed charset");
        }
        CharsetDecoder decoder =
                charset.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded =
                    decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
            return Optional.of(decoded.toString());
        } catch (CharacterCodingException undecodable) {
            // The exception says only where; the bytes stay where they are.
            return Optional.empty();
        }
    }

    /** The upper-cased form used to compare a declaration, independent of the JVM's locale. */
    static String upper(String text) {
        return text.toUpperCase(Locale.ROOT);
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if ((bytes[index] & 0xFF) != prefix[index]) {
                return false;
            }
        }
        return true;
    }
}
