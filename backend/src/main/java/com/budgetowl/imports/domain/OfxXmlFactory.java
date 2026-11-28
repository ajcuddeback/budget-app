package com.budgetowl.imports.domain;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;

/**
 * <b>The one place in the application that creates an XML parser.</b> {@code ArchitectureTest}
 * fails the build if any other class calls {@code XMLInputFactory.newInstance}, {@code newFactory}
 * or {@code newDefaultFactory}, or uses {@code DocumentBuilderFactory}, {@code SAXParserFactory} or
 * a {@code TransformerFactory} at all.
 *
 * <p>Configured <b>per factory instance</b>, never through system properties, which would change
 * every parser in the JVM (docs/features/file-import.md rule 5):
 *
 * <ul>
 *   <li>{@code SUPPORT_DTD = false} and {@code IS_SUPPORTING_EXTERNAL_ENTITIES = false};
 *   <li>{@code ACCESS_EXTERNAL_DTD} and {@code ACCESS_EXTERNAL_SCHEMA} set to {@code ""}, so no
 *       protocol is allowed for either even if something above were bypassed;
 *   <li>{@code IS_COALESCING = false}, so the leaf-length cap applies per text event rather than to
 *       one string the parser has already built;
 *   <li>{@code IS_REPLACING_ENTITY_REFERENCES = false}, so an entity reference surfaces as an event
 *       the reader refuses rather than being expanded.
 * </ul>
 *
 * <p>These flags are not the control on their own — flags have been bypassed before. {@link
 * OfxXmlReader} also refuses the file on the {@code DTD} event itself, whatever the parser would
 * have done with it.
 *
 * <p>{@code newDefaultFactory()} rather than {@code newInstance()}: the latter consults the service
 * loader, so a library added to the classpath for some other reason (Woodstox, Aalto) would
 * silently become this parser, with its own defaults. The JDK's built-in implementation is the one
 * these flags were checked against.
 */
public final class OfxXmlFactory {

    private OfxXmlFactory() {}

    public static XMLInputFactory newInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setProperty(XMLInputFactory.IS_VALIDATING, false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        // No resolver may fetch anything, should any code path ever ask for one.
        factory.setXMLResolver(
                (publicId, systemId, baseUri, namespace) -> {
                    throw new javax.xml.stream.XMLStreamException("external resolution refused");
                });
        return factory;
    }
}
