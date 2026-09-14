package io.signalharvester.collection.source.extract;

import io.signalharvester.collection.source.ExtractedSourceItem;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceType;
import jakarta.inject.Singleton;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Routes fetched responses to the configured type-specific extraction strategy. */
@Singleton
public final class DefaultSourceItemExtractor implements SourceItemExtractor {

    private final RssAtomItemExtractor rssAtomItemExtractor;
    private final JsonSourceItemExtractor jsonSourceItemExtractor;
    private final HtmlSourceItemExtractor htmlSourceItemExtractor;

    public DefaultSourceItemExtractor(
            RssAtomItemExtractor rssAtomItemExtractor,
            JsonSourceItemExtractor jsonSourceItemExtractor,
            HtmlSourceItemExtractor htmlSourceItemExtractor) {
        this.rssAtomItemExtractor = Objects.requireNonNull(rssAtomItemExtractor, "rssAtomItemExtractor");
        this.jsonSourceItemExtractor = Objects.requireNonNull(jsonSourceItemExtractor, "jsonSourceItemExtractor");
        this.htmlSourceItemExtractor = Objects.requireNonNull(htmlSourceItemExtractor, "htmlSourceItemExtractor");
    }

    @Override
    public List<ExtractedSourceItem> extract(ConfiguredSource source, FetchedSourceContent fetchedContent) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(fetchedContent, "fetchedContent");
        if (!source.id().equals(fetchedContent.sourceId())) {
            throw new SourceItemExtractionException(
                    source.id(), "Fetched content source id does not match configured source id");
        }

        boolean jsonConfigured = jsonSourceItemExtractor.isConfigured(source);
        boolean htmlConfigured = htmlSourceItemExtractor.isConfigured(source);
        if (source.type() == SourceType.RSS) {
            rejectIncompatibleGenericSettings(source, jsonConfigured, htmlConfigured);
            return rssAtomItemExtractor.extract(source, fetchedContent);
        }
        if (source.type() == SourceType.REST) {
            if (htmlConfigured) {
                throw new SourceItemExtractionException(
                        source.id(), "html.* extraction settings require source type HTML");
            }
            return jsonConfigured
                    ? jsonSourceItemExtractor.extract(source, fetchedContent)
                    : List.of(passthrough(fetchedContent));
        }
        if (jsonConfigured) {
            throw new SourceItemExtractionException(
                    source.id(), "json.* extraction settings require source type REST");
        }
        return htmlConfigured
                ? htmlSourceItemExtractor.extract(source, fetchedContent)
                : List.of(passthrough(fetchedContent));
    }

    private static void rejectIncompatibleGenericSettings(
            ConfiguredSource source, boolean jsonConfigured, boolean htmlConfigured) {
        if (jsonConfigured || htmlConfigured) {
            throw new SourceItemExtractionException(
                    source.id(), "json.* and html.* extraction settings are not valid for source type RSS");
        }
    }

    private static ExtractedSourceItem passthrough(FetchedSourceContent fetchedContent) {
        String contentType = fetchedContent.contentType().orElse("application/octet-stream");
        byte[] body = fetchedContent.body();
        try {
            return new ExtractedSourceItem(
                    fetchedContent.sourceId(),
                    fetchedContent.requestedUri(),
                    java.util.Optional.empty(),
                    java.util.Optional.empty(),
                    new String(body, charset(contentType)),
                    contentType,
                    java.util.Optional.empty(),
                    fetchedContent.fetchedAt(),
                    body);
        } catch (RuntimeException failure) {
            throw new SourceItemExtractionException(
                    fetchedContent.sourceId(), "Failed to decode fetched response body", failure);
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
