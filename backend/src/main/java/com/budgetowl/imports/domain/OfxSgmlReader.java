package com.budgetowl.imports.domain;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Reads OFX 1.x — the SGML form, including QFX — with a small tokenizer written for OFX
 * (docs/features/file-import.md rule 5).
 *
 * <p><b>It is not an SGML parser and not an XML parser, and nothing here hands the text to one.</b>
 * It has no DTD, no entity declarations and no marked sections: {@code <!} anywhere in the body
 * ({@code <!DOCTYPE}, {@code <!ENTITY}, {@code <![CDATA[}) refuses the file with reason {@code
 * dtd}, and an entity name is never looked up. Rewriting SGML into XML by closing the unclosed
 * leaves and handing the result to an XML parser is the common shortcut; it inherits every XML
 * parser risk and its auto-closing heuristics are fragile against exactly the malformed input an
 * attacker sends.
 *
 * <p><b>How a tag is told apart.</b> {@code <NAME>} followed by text is a leaf, and a {@code
 * </NAME>} straight after the text closes it. {@code <NAME>} followed by nothing but whitespace is
 * an aggregate if the file closes that name anywhere ({@code </NAME>}), and an empty leaf if it
 * never does. That is a rule, not a heuristic: it is decided from the file alone, the same way
 * every time, and a file it cannot reconcile — a close that matches nothing open, an aggregate
 * never closed — is refused rather than repaired.
 *
 * <p>In leaf text only {@code &amp; &lt; &gt; &quot; &apos;} and numeric references to allowed code
 * points are decoded. Any other {@code &name;} marks the row unreadable ({@code
 * row-entity-unknown}). A bare {@code &} that does not form a reference stays as it is, because
 * SGML OFX files routinely carry {@code AT&T}.
 */
final class OfxSgmlReader {

    /** Each header key at most once. */
    private static final Set<String> HEADER_KEYS =
            Set.of(
                    "OFXHEADER",
                    "DATA",
                    "VERSION",
                    "SECURITY",
                    "ENCODING",
                    "CHARSET",
                    "COMPRESSION",
                    "OLDFILEUID",
                    "NEWFILEUID");

    private static final Set<String> REQUIRED_HEADER_KEYS =
            Set.of("OFXHEADER", "DATA", "VERSION", "SECURITY", "ENCODING", "CHARSET");

    /** A header longer than this is not an OFX header. */
    private static final int MAXIMUM_HEADER_LENGTH = 2048;

    private final ImportLimits limits;
    private final ParseDeadline deadline;

    OfxSgmlReader(ImportLimits limits, ParseDeadline deadline) {
        this.limits = limits;
        this.deadline = deadline;
    }

    RawStatement read(String text) {
        int bodyStart = text.indexOf('<');
        if (bodyStart < 0 || bodyStart > MAXIMUM_HEADER_LENGTH) {
            throw ImportRefusal.malformed(MalformedReason.HEADER);
        }
        checkHeader(readHeader(text.substring(0, bodyStart)));

        Set<String> closedNames = closedNames(text, bodyStart);
        OfxStatementBuilder builder = new OfxStatementBuilder(limits, deadline);
        Deque<String> open = new ArrayDeque<>();
        String lastLeaf = null;
        int elements = 0;
        int position = bodyStart;
        int length = text.length();

        while (position < length) {
            char character = text.charAt(position);
            if (character != '<') {
                int next = nextTag(text, position);
                if (!text.substring(position, next).isBlank()) {
                    // Text that does not follow an opening tag belongs to nothing.
                    throw ImportRefusal.malformed(MalformedReason.NOT_A_STATEMENT);
                }
                position = next;
                continue;
            }
            if (position + 1 < length && text.charAt(position + 1) == '!') {
                throw ImportRefusal.malformed(MalformedReason.DTD);
            }
            boolean closing = position + 1 < length && text.charAt(position + 1) == '/';
            int nameStart = position + (closing ? 2 : 1);
            int nameEnd = tagNameEnd(text, nameStart);
            String name = text.substring(nameStart, nameEnd);
            position = nameEnd + 1;

            if (closing) {
                if (!open.isEmpty() && open.peek().equals(name)) {
                    open.pop();
                    builder.close(name);
                } else if (!name.equals(lastLeaf)) {
                    throw ImportRefusal.malformed(MalformedReason.NOT_A_STATEMENT);
                }
                lastLeaf = null;
                continue;
            }

            elements++;
            if (elements > limits.ofxMaximumElements()) {
                throw ImportRefusal.malformed(MalformedReason.TOO_MANY_ELEMENTS);
            }
            if (elements % ImportLimits.DEADLINE_STRIDE == 0) {
                deadline.checkNow();
            }

            int textEnd = nextTag(text, position);
            if (textEnd - position > limits.ofxMaximumLeafLength()) {
                throw ImportRefusal.malformed(MalformedReason.ELEMENT_TOO_LONG);
            }
            String leafText = text.substring(position, textEnd).strip();
            boolean closedStraightAfter = text.startsWith("</" + name + ">", textEnd);

            if (!leafText.isEmpty() || closedStraightAfter || !closedNames.contains(name)) {
                Decoded decoded = decodeEntities(leafText);
                builder.leaf(name, decoded.text(), decoded.problem());
                lastLeaf = name;
            } else {
                open.push(name);
                if (open.size() > limits.ofxMaximumDepth()) {
                    throw ImportRefusal.malformed(MalformedReason.TOO_DEEP);
                }
                builder.open(name);
                lastLeaf = null;
            }
            position = textEnd;
        }
        if (!open.isEmpty()) {
            throw ImportRefusal.malformed(MalformedReason.NOT_A_STATEMENT);
        }
        return builder.finish();
    }

    // ---------------------------------------------------------------------------------- header

    private static Map<String, String> readHeader(String header) {
        Map<String, String> values = new HashMap<>();
        for (String line : header.split("\\r\\n|\\n|\\r")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                throw ImportRefusal.malformed(MalformedReason.HEADER);
            }
            String key = trimmed.substring(0, colon).strip();
            String value = trimmed.substring(colon + 1).strip();
            if (!HEADER_KEYS.contains(key) || values.put(key, value) != null) {
                // An unknown key, or one given twice.
                throw ImportRefusal.malformed(MalformedReason.HEADER);
            }
        }
        if (!values.keySet().containsAll(REQUIRED_HEADER_KEYS)) {
            throw ImportRefusal.malformed(MalformedReason.HEADER);
        }
        return values;
    }

    private static void checkHeader(Map<String, String> header) {
        if (!"100".equals(header.get("OFXHEADER"))
                || !"OFXSGML".equals(header.get("DATA"))
                || !header.get("VERSION").matches("^1[0-9]{2}$")) {
            throw ImportRefusal.malformed(MalformedReason.HEADER);
        }
        if (!"NONE".equals(header.get("SECURITY"))) {
            throw ImportRefusal.malformed(MalformedReason.ENCRYPTED);
        }
        String compression = header.get("COMPRESSION");
        if (compression != null && !"NONE".equals(compression)) {
            throw ImportRefusal.malformed(MalformedReason.COMPRESSED);
        }
    }

    // ------------------------------------------------------------------------------- tokenizer

    /**
     * Every name the body closes somewhere, which is what makes {@code <NAME>} an aggregate. One
     * linear pass, bounded by the same element cap as the main one.
     */
    private Set<String> closedNames(String text, int from) {
        Set<String> names = new HashSet<>();
        int position = text.indexOf("</", from);
        int seen = 0;
        while (position >= 0) {
            if (++seen > limits.ofxMaximumElements()) {
                throw ImportRefusal.malformed(MalformedReason.TOO_MANY_ELEMENTS);
            }
            int nameEnd = tagNameEnd(text, position + 2);
            names.add(text.substring(position + 2, nameEnd));
            position = text.indexOf("</", nameEnd);
        }
        return names;
    }

    /**
     * Where a tag name ends — at its {@code >}. Names are letters, digits, dots and underscores
     * ({@code INTU.USERID}), at most {@code ofxMaximumNameLength} of them.
     */
    private int tagNameEnd(String text, int from) {
        int position = from;
        int limit = Math.min(text.length(), from + limits.ofxMaximumNameLength() + 1);
        while (position < limit) {
            char character = text.charAt(position);
            if (character == '>') {
                if (position == from) {
                    throw ImportRefusal.malformed(MalformedReason.NOT_A_STATEMENT);
                }
                return position;
            }
            boolean nameCharacter =
                    (character >= 'A' && character <= 'Z')
                            || (character >= 'a' && character <= 'z')
                            || (character >= '0' && character <= '9')
                            || character == '.'
                            || character == '_';
            if (!nameCharacter) {
                throw ImportRefusal.malformed(
                        character == '!' ? MalformedReason.DTD : MalformedReason.NOT_A_STATEMENT);
            }
            position++;
        }
        if (position - from > limits.ofxMaximumNameLength()) {
            throw ImportRefusal.malformed(MalformedReason.ELEMENT_TOO_LONG);
        }
        throw ImportRefusal.malformed(MalformedReason.NOT_A_STATEMENT);
    }

    private static int nextTag(String text, int from) {
        int next = text.indexOf('<', from);
        return next < 0 ? text.length() : next;
    }

    // -------------------------------------------------------------------------------- entities

    record Decoded(String text, boolean problem) {}

    /**
     * Decodes the five predefined entities and numeric references; anything else of the shape
     * {@code &name;} is a row problem and is left undecoded.
     */
    static Decoded decodeEntities(String text) {
        if (text.indexOf('&') < 0) {
            return new Decoded(text, false);
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean problem = false;
        int index = 0;
        while (index < text.length()) {
            char character = text.charAt(index);
            if (character != '&') {
                out.append(character);
                index++;
                continue;
            }
            int semicolon = referenceEnd(text, index);
            if (semicolon < 0) {
                out.append(character);
                index++;
                continue;
            }
            String reference = text.substring(index + 1, semicolon);
            int codePoint = resolve(reference);
            if (codePoint < 0) {
                problem = true;
                out.append(text, index, semicolon + 1);
            } else {
                out.appendCodePoint(codePoint);
            }
            index = semicolon + 1;
        }
        return new Decoded(out.toString(), problem);
    }

    /** The {@code ;} closing a reference that starts at {@code ampersand}, or -1 if none does. */
    private static int referenceEnd(String text, int ampersand) {
        int position = ampersand + 1;
        int limit = Math.min(text.length(), ampersand + 34);
        if (position >= limit) {
            return -1;
        }
        char first = text.charAt(position);
        boolean starts =
                first == '#' || (first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z');
        if (!starts) {
            return -1;
        }
        for (position++; position < limit; position++) {
            char character = text.charAt(position);
            if (character == ';') {
                return position;
            }
            boolean continues =
                    (character >= 'A' && character <= 'Z')
                            || (character >= 'a' && character <= 'z')
                            || (character >= '0' && character <= '9');
            if (!continues) {
                return -1;
            }
        }
        return -1;
    }

    /** The code point a reference stands for, or -1 when it is not one this reader decodes. */
    private static int resolve(String reference) {
        switch (reference) {
            case "amp" -> {
                return '&';
            }
            case "lt" -> {
                return '<';
            }
            case "gt" -> {
                return '>';
            }
            case "quot" -> {
                return '"';
            }
            case "apos" -> {
                return '\'';
            }
            default -> {
                // Numeric, or not ours.
            }
        }
        if (!reference.startsWith("#") || reference.length() < 2 || reference.length() > 9) {
            return -1;
        }
        boolean hex = reference.charAt(1) == 'x' || reference.charAt(1) == 'X';
        String digits = reference.substring(hex ? 2 : 1);
        if (digits.isEmpty()) {
            return -1;
        }
        int value = 0;
        for (int index = 0; index < digits.length(); index++) {
            int digit = Character.digit(digits.charAt(index), hex ? 16 : 10);
            boolean ascii = digits.charAt(index) < 128;
            if (digit < 0 || !ascii) {
                return -1;
            }
            value = value * (hex ? 16 : 10) + digit;
            if (value > Character.MAX_CODE_POINT) {
                return -1;
            }
        }
        boolean allowed =
                value != 0
                        && !(value >= Character.MIN_SURROGATE && value <= Character.MAX_SURROGATE);
        return allowed ? value : -1;
    }
}
