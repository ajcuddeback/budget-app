package com.budgetowl.imports.domain;

import com.budgetowl.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises what a file is, so the declared format can be <b>verified</b> rather than trusted
 * (docs/features/file-import.md rule 2), and reads what its header declares about its encoding
 * (rule 4, step 2).
 *
 * <p>One decision from the bytes, made once. The server never decides "this is probably CSV" on its
 * own, and never tries several parsers in turn on the same bytes.
 */
final class FormatSniffer {

    /** What the content is, as far as recognising it goes. */
    enum Kind {
        OFX_SGML,
        OFX_XML,
        QIF,
        /** None of the above: only a file declared as CSV may be this. */
        OTHER
    }

    /** How far into a file its header must be found. */
    private static final int HEADER_WINDOW = 2048;

    private static final Pattern XML_ENCODING =
            Pattern.compile("encoding\\s*=\\s*[\"']([A-Za-z0-9._-]{1,40})[\"']");

    private static final Pattern OFX_PI = Pattern.compile("<\\?OFX\\s");

    private FormatSniffer() {}

    static Kind sniff(String text) {
        String start = leadingTrimmed(text);
        if (start.startsWith("OFXHEADER:")) {
            return Kind.OFX_SGML;
        }
        if (start.startsWith("<?xml") || start.startsWith("<?OFX")) {
            // The OFX processing instruction belongs in the prolog, before the root element. A
            // DOCTYPE can push it any distance from the start — which is exactly the file that
            // must reach the reader, so the reader can refuse it as a DTD rather than this
            // calling it "not OFX".
            int root = start.indexOf("<OFX");
            while (root >= 0 && start.startsWith("<OFXHEADER", root)) {
                root = start.indexOf("<OFX", root + 1);
            }
            String prolog = root < 0 ? start : start.substring(0, root);
            return OFX_PI.matcher(prolog).find() ? Kind.OFX_XML : Kind.OTHER;
        }
        if (start.startsWith("!")) {
            int end = start.indexOf('\n');
            String first =
                    (end < 0 ? start : start.substring(0, end)).strip().toUpperCase(Locale.ROOT);
            if (first.startsWith("!TYPE:")
                    || first.startsWith("!OPTION:")
                    || first.startsWith("!CLEAR:")
                    || first.equals("!ACCOUNT")) {
                return Kind.QIF;
            }
        }
        return Kind.OTHER;
    }

    /**
     * The charset a header declares, if the bytes begin with one that does.
     *
     * <p>Read from the raw bytes as ISO-8859-1 — one byte, one character — because the declaration
     * itself is ASCII in every format that has one, and it has to be read before anything is
     * decoded. Only the first {@value #HEADER_WINDOW} bytes are looked at.
     *
     * @return the allowed name, or empty when nothing is declared — including OFX's {@code
     *     CHARSET:NONE}, which declares nothing
     * @throws ImportRefusal {@code import-encoding-unsupported} for a declaration outside the
     *     allowlist, UTF-7 included
     */
    static Optional<String> declaredCharset(byte[] bytes) {
        int window = Math.min(bytes.length, HEADER_WINDOW);
        String head = leadingTrimmed(new String(bytes, 0, window, StandardCharsets.ISO_8859_1));
        if (head.startsWith("OFXHEADER:")) {
            int body = head.indexOf('<');
            return ofxDeclaration(body < 0 ? head : head.substring(0, body));
        }
        if (head.startsWith("<?xml")) {
            int end = head.indexOf("?>");
            String declaration = end < 0 ? head : head.substring(0, end);
            Matcher matcher = XML_ENCODING.matcher(declaration);
            if (!matcher.find()) {
                // XML without an encoding declaration is UTF-8 by definition.
                return Optional.of("UTF-8");
            }
            return Optional.of(allowedOrRefuse(matcher.group(1)));
        }
        return Optional.empty();
    }

    /** OFX 1.x: {@code ENCODING:USASCII} with {@code CHARSET:1252}, or {@code ENCODING:UTF-8}. */
    private static Optional<String> ofxDeclaration(String header) {
        String encoding = null;
        String charset = null;
        for (String line : header.split("\\r\\n|\\n|\\r")) {
            String trimmed = line.strip().toUpperCase(Locale.ROOT);
            if (trimmed.startsWith("ENCODING:")) {
                encoding = trimmed.substring(9).strip();
            } else if (trimmed.startsWith("CHARSET:")) {
                charset = trimmed.substring(8).strip();
            }
        }
        if (encoding == null) {
            // The reader refuses a header without one; that is its job, not this method's.
            return Optional.empty();
        }
        if (encoding.equals("UTF-8") || encoding.equals("UTF8")) {
            return Optional.of("UTF-8");
        }
        if (!encoding.equals("USASCII")) {
            throw ImportRefusal.of(ErrorCode.IMPORT_ENCODING_UNSUPPORTED);
        }
        if (charset == null || charset.equals("NONE")) {
            return Optional.empty();
        }
        return Optional.of(
                switch (charset) {
                    case "1252", "WINDOWS-1252", "CP1252" -> "windows-1252";
                    case "1250", "WINDOWS-1250" -> "windows-1250";
                    case "1251", "WINDOWS-1251" -> "windows-1251";
                    case "ISO-8859-1", "8859-1", "ISO8859-1", "LATIN1" -> "ISO-8859-1";
                    default -> throw ImportRefusal.of(ErrorCode.IMPORT_ENCODING_UNSUPPORTED);
                });
    }

    private static String allowedOrRefuse(String declared) {
        String upper = declared.toUpperCase(Locale.ROOT);
        if (upper.equals("US-ASCII") || upper.equals("ASCII")) {
            // ASCII is a subset of UTF-8, and a strict UTF-8 decode is stricter than ASCII needs.
            return "UTF-8";
        }
        return ImportCharsets.canonicalName(declared)
                .orElseThrow(() -> ImportRefusal.of(ErrorCode.IMPORT_ENCODING_UNSUPPORTED));
    }

    private static String leadingTrimmed(String text) {
        int start = 0;
        while (start < text.length()
                && (Character.isWhitespace(text.charAt(start)) || text.charAt(start) == '\uFEFF')) {
            start++;
        }
        return text.substring(start);
    }
}
