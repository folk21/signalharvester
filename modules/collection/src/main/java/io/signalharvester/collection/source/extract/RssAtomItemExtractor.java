package io.signalharvester.collection.source.extract;

import io.signalharvester.collection.source.ExtractedSourceItem;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.configuration.api.ConfiguredSource;
import jakarta.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/** Extracts RSS 2.x items and Atom entries from bounded fetched XML responses. */
@Singleton
public final class RssAtomItemExtractor {

    private static final String TEXT_CONTENT_TYPE = "text/plain; charset=UTF-8";
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final ErrorHandler STRICT_ERROR_HANDLER = new ErrorHandler() {
        @Override
        public void warning(SAXParseException exception) {
            // Warnings do not make an otherwise valid bounded feed unusable.
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    };

    private final RssExtractionConfiguration configuration;

    public RssAtomItemExtractor(RssExtractionConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    /** Parses one RSS/Atom response into deterministic semantic collection items. */
    List<ExtractedSourceItem> extract(ConfiguredSource source, FetchedSourceContent fetchedContent) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(fetchedContent, "fetchedContent");

        Document document = parse(source, fetchedContent.body());
        List<Element> entries = entries(document);
        int maxItems = configuration.getMaxItemsPerSource();
        if (entries.size() > maxItems) {
            throw new SourceItemExtractionException(
                    source.id(),
                    "RSS/Atom response contains " + entries.size()
                            + " items, exceeding configured maximum " + maxItems);
        }

        List<ExtractedSourceItem> items = new ArrayList<>(entries.size());
        for (Element entry : entries) {
            items.add(toItem(source, fetchedContent, entry));
        }
        return List.copyOf(items);
    }

    private static Document parse(ConfiguredSource source, byte[] body) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(STRICT_ERROR_HANDLER);
            return builder.parse(new ByteArrayInputStream(body));
        } catch (SAXException | java.io.IOException | RuntimeException | javax.xml.parsers.ParserConfigurationException failure) {
            throw new SourceItemExtractionException(source.id(), "Failed to parse RSS/Atom response", failure);
        }
    }

    private static void setFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (javax.xml.parsers.ParserConfigurationException unsupported) {
            throw new IllegalStateException("XML parser does not support required secure feature: " + feature, unsupported);
        }
    }

    private static List<Element> entries(Document document) {
        List<Element> rssItems = elements(document, "item");
        if (!rssItems.isEmpty()) {
            return rssItems;
        }
        return elements(document, "entry");
    }

    private static List<Element> elements(Document document, String localName) {
        NodeList nodes = document.getElementsByTagNameNS("*", localName);
        List<Element> result = new ArrayList<>(nodes.getLength());
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element) {
                result.add(element);
            }
        }
        if (!result.isEmpty()) {
            return result;
        }

        NodeList unqualified = document.getElementsByTagName(localName);
        for (int index = 0; index < unqualified.getLength(); index++) {
            Node node = unqualified.item(index);
            if (node instanceof Element element) {
                result.add(element);
            }
        }
        return result;
    }

    private static ExtractedSourceItem toItem(
            ConfiguredSource source,
            FetchedSourceContent fetchedContent,
            Element entry) {
        Optional<String> explicitId = firstChildText(entry, "guid", "id");
        Optional<String> title = firstChildText(entry, "title").map(RssAtomItemExtractor::normalizeText);
        Optional<String> linkText = rssLink(entry);
        Optional<String> atomLink = atomLink(entry);
        Optional<URI> resolvedItemUrl = itemUrl(fetchedContent.requestedUri(), atomLink.or(() -> linkText));
        URI itemUrl = resolvedItemUrl.orElseGet(() -> withoutFragment(fetchedContent.requestedUri()));
        Optional<String> externalId = explicitId.map(String::strip)
                .filter(value -> !value.isEmpty())
                .or(() -> resolvedItemUrl.map(URI::toString));

        String body = firstChildText(entry, "encoded", "content", "description", "summary")
                .map(RssAtomItemExtractor::plainText)
                .filter(value -> !value.isBlank())
                .orElseGet(() -> title.orElse(itemUrl.toString()));
        Optional<Instant> publishedAt = firstChildText(entry, "pubDate", "published", "updated", "date")
                .flatMap(RssAtomItemExtractor::parseInstant);
        byte[] identityPayload = identityPayload(externalId, title, itemUrl, body, publishedAt);

        return new ExtractedSourceItem(
                source.id(),
                itemUrl,
                externalId,
                title.filter(value -> !value.isBlank()),
                body,
                TEXT_CONTENT_TYPE,
                publishedAt,
                fetchedContent.fetchedAt(),
                identityPayload);
    }

    private static Optional<String> rssLink(Element entry) {
        for (Element child : directChildren(entry)) {
            if ("link".equals(localName(child)) && !child.hasAttribute("href")) {
                String value = child.getTextContent().strip();
                if (!value.isEmpty()) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> atomLink(Element entry) {
        String fallback = null;
        for (Element child : directChildren(entry)) {
            if (!"link".equals(localName(child)) || !child.hasAttribute("href")) {
                continue;
            }
            String href = child.getAttribute("href").strip();
            if (href.isEmpty()) {
                continue;
            }
            String rel = child.getAttribute("rel").strip().toLowerCase(Locale.ROOT);
            if (rel.isEmpty() || "alternate".equals(rel)) {
                return Optional.of(href);
            }
            if (fallback == null) {
                fallback = href;
            }
        }
        return Optional.ofNullable(fallback);
    }

    private static Optional<String> firstChildText(Element parent, String... candidateNames) {
        List<Element> children = directChildren(parent);
        for (String candidateName : candidateNames) {
            for (Element child : children) {
                if (candidateName.equals(localName(child))) {
                    String value = child.getTextContent().strip();
                    if (!value.isEmpty()) {
                        return Optional.of(value);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static List<Element> directChildren(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node instanceof Element element) {
                result.add(element);
            }
        }
        return result;
    }

    private static String localName(Element element) {
        String localName = element.getLocalName();
        if (localName != null && !localName.isBlank()) {
            return localName;
        }
        String nodeName = element.getNodeName();
        int separator = nodeName.indexOf(':');
        return separator >= 0 ? nodeName.substring(separator + 1) : nodeName;
    }

    private static Optional<URI> itemUrl(URI sourceUri, Optional<String> candidate) {
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        try {
            URI resolved = sourceUri.resolve(candidate.orElseThrow().strip());
            if (!resolved.isAbsolute()
                    || resolved.getHost() == null
                    || resolved.getUserInfo() != null
                    || !("http".equalsIgnoreCase(resolved.getScheme())
                    || "https".equalsIgnoreCase(resolved.getScheme()))) {
                return Optional.empty();
            }
            return Optional.of(withoutFragment(resolved));
        } catch (IllegalArgumentException failure) {
            return Optional.empty();
        }
    }

    private static URI withoutFragment(URI uri) {
        if (uri.getFragment() == null) {
            return uri;
        }
        try {
            return new URI(
                    uri.getScheme(),
                    uri.getUserInfo(),
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath(),
                    uri.getQuery(),
                    null);
        } catch (URISyntaxException impossible) {
            throw new IllegalArgumentException("Unable to remove URI fragment", impossible);
        }
    }

    private static String plainText(String value) {
        return normalizeText(HTML_TAG.matcher(value).replaceAll(" "));
    }

    private static String normalizeText(String value) {
        String stripped = value.strip();
        return stripped.isEmpty() ? "" : WHITESPACE.matcher(stripped).replaceAll(" ");
    }

    private static Optional<Instant> parseInstant(String value) {
        String candidate = value.strip();
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.RFC_1123_DATE_TIME,
                DateTimeFormatter.ISO_DATE_TIME)) {
            try {
                if (formatter == DateTimeFormatter.RFC_1123_DATE_TIME) {
                    return Optional.of(ZonedDateTime.parse(candidate, formatter).toInstant());
                }
                return Optional.of(OffsetDateTime.parse(candidate, formatter).toInstant());
            } catch (DateTimeParseException ignored) {
                // Try the next supported feed timestamp representation.
            }
        }
        try {
            return Optional.of(Instant.parse(candidate));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private static byte[] identityPayload(
            Optional<String> externalId,
            Optional<String> title,
            URI url,
            String content,
            Optional<Instant> publishedAt) {
        String canonical = String.join("\u0000",
                externalId.orElse(""),
                title.orElse(""),
                url.toASCIIString(),
                content,
                publishedAt.map(Instant::toString).orElse(""));
        return canonical.getBytes(StandardCharsets.UTF_8);
    }
}
