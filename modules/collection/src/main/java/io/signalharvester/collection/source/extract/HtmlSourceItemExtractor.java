package io.signalharvester.collection.source.extract;

import io.signalharvester.collection.source.ExtractedSourceItem;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.configuration.api.ConfiguredSource;
import jakarta.inject.Singleton;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** Extracts semantic items from HTML responses using persisted CSS selector settings. */
@Singleton
public final class HtmlSourceItemExtractor {

    private static final String TEXT_CONTENT_TYPE = "text/plain; charset=UTF-8";

    private final GenericExtractionConfiguration configuration;

    public HtmlSourceItemExtractor(GenericExtractionConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    /** Returns whether the persisted source settings opt into CSS-selector extraction. */
    boolean isConfigured(ConfiguredSource source) {
        return source.settings().keySet().stream().anyMatch(key -> key.startsWith(SourceExtractionSettings.HTML_PREFIX));
    }

    /** Parses one HTML response into bounded semantic items. */
    List<ExtractedSourceItem> extract(ConfiguredSource source, FetchedSourceContent fetchedContent) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(fetchedContent, "fetchedContent");
        String itemSelector = requiredSetting(source, SourceExtractionSettings.HTML_ITEM_SELECTOR);
        Document document;
        try {
            document = Jsoup.parse(
                    decode(fetchedContent),
                    fetchedContent.requestedUri().toString());
        } catch (RuntimeException failure) {
            throw new SourceItemExtractionException(source.id(), "Failed to parse HTML response", failure);
        }

        List<Element> candidateElements;
        try {
            candidateElements = List.copyOf(document.select(itemSelector));
        } catch (RuntimeException failure) {
            throw new SourceItemExtractionException(source.id(), "html.itemSelector is not a valid CSS selector", failure);
        }
        int maxItems = configuration.getMaxItemsPerSource();
        if (candidateElements.size() > maxItems) {
            throw new SourceItemExtractionException(
                    source.id(),
                    "HTML response contains " + candidateElements.size()
                            + " candidate items, exceeding configured maximum " + maxItems);
        }

        List<ExtractedSourceItem> items = new ArrayList<>(candidateElements.size());
        for (Element candidate : candidateElements) {
            items.add(toItem(source, fetchedContent, candidate));
        }
        return List.copyOf(items);
    }

    private static ExtractedSourceItem toItem(
            ConfiguredSource source,
            FetchedSourceContent fetchedContent,
            Element candidate) {
        Map<String, String> settings = source.settings();
        String contentSelector = settings.get(SourceExtractionSettings.HTML_CONTENT_SELECTOR);
        Element contentElement = contentSelector == null
                ? candidate
                : selectedElement(
                                source, candidate, contentSelector, SourceExtractionSettings.HTML_CONTENT_SELECTOR)
                        .orElseThrow(() -> new SourceItemExtractionException(
                                source.id(), "html.contentSelector did not match a candidate item"));
        String content = contentElement.text().strip();
        if (content.isBlank()) {
            throw new SourceItemExtractionException(source.id(), "HTML candidate item contains no extracted content");
        }

        Optional<String> title = selectedValue(
                source,
                candidate,
                settings.get(SourceExtractionSettings.HTML_TITLE_SELECTOR),
                null,
                SourceExtractionSettings.HTML_TITLE_SELECTOR);
        Optional<String> externalId = selectedValue(
                source,
                candidate,
                settings.get(SourceExtractionSettings.HTML_EXTERNAL_ID_SELECTOR),
                settings.get(SourceExtractionSettings.HTML_EXTERNAL_ID_ATTRIBUTE),
                SourceExtractionSettings.HTML_EXTERNAL_ID_SELECTOR);
        String urlSelector = settings.get(SourceExtractionSettings.HTML_URL_SELECTOR);
        String urlAttribute = settings.getOrDefault(SourceExtractionSettings.HTML_URL_ATTRIBUTE, "href");
        Optional<String> urlValue = selectedValue(
                source, candidate, urlSelector, urlAttribute, SourceExtractionSettings.HTML_URL_SELECTOR);
        var itemUrl = GenericExtractionSupport.resolveItemUrl(source.id(), fetchedContent.requestedUri(), urlValue);
        Optional<String> publishedValue = selectedValue(
                source,
                candidate,
                settings.get(SourceExtractionSettings.HTML_PUBLISHED_AT_SELECTOR),
                settings.get(SourceExtractionSettings.HTML_PUBLISHED_AT_ATTRIBUTE),
                SourceExtractionSettings.HTML_PUBLISHED_AT_SELECTOR);
        var publishedAt = GenericExtractionSupport.parseOptionalInstant(source.id(), publishedValue);
        Optional<String> normalizedExternalId = GenericExtractionSupport.nonBlank(externalId);
        Optional<String> normalizedTitle = GenericExtractionSupport.nonBlank(title);

        return new ExtractedSourceItem(
                source.id(),
                itemUrl,
                normalizedExternalId,
                normalizedTitle,
                content,
                TEXT_CONTENT_TYPE,
                publishedAt,
                fetchedContent.fetchedAt(),
                GenericExtractionSupport.identityPayload(
                        normalizedExternalId, normalizedTitle, itemUrl, content, publishedAt));
    }

    private static Optional<Element> selectedElement(
            ConfiguredSource source,
            Element candidate,
            String selector,
            String settingName) {
        if (selector == null) {
            return Optional.empty();
        }
        if (selector.isBlank()) {
            throw new SourceItemExtractionException(source.id(), settingName + " must not be blank when supplied");
        }
        try {
            return Optional.ofNullable(candidate.selectFirst(selector));
        } catch (RuntimeException failure) {
            throw new SourceItemExtractionException(source.id(), settingName + " is not a valid CSS selector", failure);
        }
    }

    private static Optional<String> selectedValue(
            ConfiguredSource source,
            Element candidate,
            String selector,
            String attribute,
            String settingName) {
        Element element;
        if (selector == null) {
            if (attribute == null || attribute.isBlank()) {
                return Optional.empty();
            }
            element = candidate;
        } else {
            Optional<Element> selected = selectedElement(source, candidate, selector, settingName);
            if (selected.isEmpty()) {
                return Optional.empty();
            }
            element = selected.orElseThrow();
        }
        String value = attribute == null || attribute.isBlank() ? element.text() : element.attr(attribute);
        return Optional.of(value);
    }

    private static String requiredSetting(ConfiguredSource source, String key) {
        String value = source.settings().get(key);
        if (value == null || value.isBlank()) {
            throw new SourceItemExtractionException(source.id(), "HTML extraction requires non-blank setting " + key);
        }
        return value;
    }

    private static String decode(FetchedSourceContent fetchedContent) {
        String contentType = fetchedContent.contentType().orElse("text/html; charset=UTF-8");
        try {
            return new String(fetchedContent.body(), charset(contentType));
        } catch (RuntimeException failure) {
            throw new SourceItemExtractionException(
                    fetchedContent.sourceId(), "Failed to decode HTML response body", failure);
        }
    }

    private static Charset charset(String contentType) {
        for (String parameter : contentType.split(";")) {
            String trimmed = parameter.trim();
            int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String name = trimmed.substring(0, separator).trim();
            if (!"charset".equals(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String value = trimmed.substring(separator + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (!value.isBlank()) {
                return Charset.forName(value);
            }
        }
        return StandardCharsets.UTF_8;
    }
}
