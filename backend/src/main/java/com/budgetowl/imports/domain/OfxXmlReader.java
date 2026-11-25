package com.budgetowl.imports.domain;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads OFX 2.x — the XML form, including QFX — with the JDK's StAX parser, DTDs refused
 * (docs/features/file-import.md rule 5).
 *
 * <p><b>A {@code DTD} event refuses the file</b> with reason {@code dtd}, even though the factory
 * is already configured to ignore one: a statement has no legitimate reason to carry a DOCTYPE, and
 * refusing on the event itself does not depend on any flag having worked. Billion-laughs,
 * quadratic-blowup and external-entity payloads all need a DTD, so all of them stop here, before a
 * single entity is declared. An entity reference is refused too, since nothing is declared.
 *
 * <p>The text has already been decoded with the charset the file declared (and that the allowlist
 * permits), so the parser is given a {@link StringReader} and never chooses an encoding itself.
 *
 * <p><b>No parser exception's message survives.</b> StAX puts the offending text and its position
 * in {@code getMessage()}, which here is somebody's bank statement; every {@link
 * XMLStreamException} is replaced — not wrapped — by a refusal with a fixed reason.
 */
final class OfxXmlReader {

    private static final Pattern PSEUDO_ATTRIBUTE = Pattern.compile("([A-Z]+)=\"([^\"]{0,64})\"");

    private final ImportLimits limits;
    private final ParseDeadline deadline;
    private final XMLInputFactory factory = OfxXmlFactory.newInputFactory();

    OfxXmlReader(ImportLimits limits, ParseDeadline deadline) {
        this.limits = limits;
        this.deadline = deadline;
    }

    RawStatement read(String text) {
        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(new StringReader(text));
            return readAll(reader);
        } catch (XMLStreamException unreadable) {
            throw ImportRefusal.malformed(MalformedReason.NOT_A_STATEMENT);
        } finally {
            close(reader);
        }
    }

    private RawStatement readAll(XMLStreamReader reader) throws XMLStreamException {
        OfxStatementBuilder builder = new OfxStatementBuilder(limits, deadline);
        Deque<Element> open = new ArrayDeque<>();
        boolean sawHeader = false;
        boolean sawRoot = false;
        int elements = 0;

        while (reader.hasNext()) {
            int event = reader.next();
            switch (event) {
                case XMLStreamConstants.DTD -> throw ImportRefusal.malformed(MalformedReason.DTD);
                case XMLStreamConstants.ENTITY_DECLARATION ->
                        throw ImportRefusal.malformed(MalformedReason.DTD);
                case XMLStreamConstants.ENTITY_REFERENCE ->
                        // Nothing is declared, so a reference names nothing: the file is not
                        // well-formed XML. Never looked up, never expanded.
                        throw ImportRefusal.malformed(MalformedReason.NOT_A_STATEMENT);
                case XMLStreamConstants.PROCESSING_INSTRUCTION -> {
                    if ("OFX".equals(reader.getPITarget())) {
                        if (sawHeader || sawRoot) {
                            throw ImportRefusal.malformed(MalformedReason.HEADER);
                        }
                        checkHeader(reader.getPIData());
                        sawHeader = true;
                    }
                    // Any other processing instruction is ignored, and XInclude does not apply
                    // to StAX.
                }
                case XMLStreamConstants.START_ELEMENT -> {
                    if (!sawHeader) {
                        throw ImportRefusal.malformed(MalformedReason.HEADER);
                    }
                    sawRoot = true;
                    elements++;
                    if (elements > limits.ofxMaximumElements()) {
                        throw ImportRefusal.malformed(MalformedReason.TOO_MANY_ELEMENTS);
                    }
                    if (elements % ImportLimits.DEADLINE_STRIDE == 0) {
                        deadline.checkNow();
                    }
                    String name = reader.getLocalName();
                    if (name.length() > limits.ofxMaximumNameLength()) {
                        throw ImportRefusal.malformed(MalformedReason.ELEMENT_TOO_LONG);
                    }
                    Element parent = open.peek();
                    if (parent != null && !parent.opened) {
                        // The parent has a child, so it is an aggregate: announce it now, before
                        // the child, so the builder sees the same order the SGML reader gives it.
                        parent.opened = true;
                        builder.open(parent.name);
                    }
                    open.push(new Element(name));
                    if (open.size() > limits.ofxMaximumDepth()) {
                        throw ImportRefusal.malformed(MalformedReason.TOO_DEEP);
                    }
                }
                case XMLStreamConstants.CHARACTERS,
                        XMLStreamConstants.CDATA,
                        XMLStreamConstants.SPACE -> {
                    Element current = open.peek();
                    if (current != null) {
                        current.append(reader.getText(), limits.ofxMaximumLeafLength());
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    Element closed = open.pop();
                    if (closed.opened) {
                        builder.close(closed.name);
                    } else {
                        builder.leaf(closed.name, closed.text.toString().strip(), false);
                    }
                }
                default -> {
                    // Comments, the document's start and end.
                }
            }
        }
        if (!sawRoot) {
            throw ImportRefusal.malformed(MalformedReason.NOT_A_STATEMENT);
        }
        return builder.finish();
    }

    /** {@code <?OFX OFXHEADER="200" VERSION="211" SECURITY="NONE" ...?>}, checked for shape. */
    private static void checkHeader(String data) {
        if (data == null || data.length() > 512) {
            throw ImportRefusal.malformed(MalformedReason.HEADER);
        }
        Map<String, String> attributes = new HashMap<>();
        Matcher matcher = PSEUDO_ATTRIBUTE.matcher(data);
        while (matcher.find()) {
            if (attributes.put(matcher.group(1), matcher.group(2)) != null) {
                throw ImportRefusal.malformed(MalformedReason.HEADER);
            }
        }
        String version = attributes.get("VERSION");
        if (!"200".equals(attributes.get("OFXHEADER"))
                || version == null
                || !version.matches("^2[0-9]{2}$")) {
            throw ImportRefusal.malformed(MalformedReason.HEADER);
        }
        if (!"NONE".equals(attributes.get("SECURITY"))) {
            throw ImportRefusal.malformed(MalformedReason.ENCRYPTED);
        }
    }

    private static void close(XMLStreamReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (XMLStreamException ignored) {
            // Nothing is held open but a StringReader; there is nothing to release or report.
        }
    }

    /** One open element: its name, its text so far, and whether it turned out to have children. */
    private static final class Element {

        private final String name;
        private final StringBuilder text = new StringBuilder();
        private boolean opened;

        Element(String name) {
            this.name = name;
        }

        void append(String more, int limit) {
            if (opened) {
                // Whitespace between an aggregate's children is not anybody's text.
                return;
            }
            if (text.length() + more.length() > limit) {
                throw ImportRefusal.malformed(MalformedReason.ELEMENT_TOO_LONG);
            }
            text.append(more);
        }
    }
}
