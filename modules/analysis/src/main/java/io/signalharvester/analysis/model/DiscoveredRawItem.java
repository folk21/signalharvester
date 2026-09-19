package io.signalharvester.analysis.model;

import static io.signalharvester.common.validation.Preconditions.requireNonBlankArgument;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Analysis-owned representation of one decoded raw discovery before normalization.
 *
 * <p>This is a semantic application model rather than a duplicate wire DTO. Kafka/Protobuf mapping
 * ends before this type enters normalization and analysis logic.</p>
 *
 * @param sourceEventId event id of the consumed RawItemDiscovered publication
 * @param correlationId collection-run correlation id
 * @param traceparent distributed tracing context when available
 * @param discoveredAt time carried by the source event envelope
 * @param rawItemId collection-owned stable raw payload identity
 * @param sourceId configured source identifier
 * @param monitoringProfileId profile in whose processing scope the item was discovered
 * @param informationCategory product information category
 * @param analysisSettings immutable deterministic settings captured for this event
 * @param externalId stable source-provided identity when available
 * @param title source-provided title when available
 * @param url source/canonical URL
 * @param content raw textual content
 * @param contentType source content type
 * @param publishedAt source publication time when available
 */
public record DiscoveredRawItem(
        String sourceEventId,
        String correlationId,
        Optional<String> traceparent,
        Instant discoveredAt,
        String rawItemId,
        String sourceId,
        String monitoringProfileId,
        String informationCategory,
        KeywordAnalysisSettings analysisSettings,
        Optional<String> externalId,
        Optional<String> title,
        URI url,
        String content,
        String contentType,
        Optional<Instant> publishedAt) {

    public DiscoveredRawItem {
        requireNonBlankArgument(sourceEventId, "sourceEventId");
        requireNonBlankArgument(correlationId, "correlationId");
        Objects.requireNonNull(traceparent, "traceparent");
        Objects.requireNonNull(discoveredAt, "discoveredAt");
        requireNonBlankArgument(rawItemId, "rawItemId");
        requireNonBlankArgument(sourceId, "sourceId");
        requireNonBlankArgument(monitoringProfileId, "monitoringProfileId");
        requireNonBlankArgument(informationCategory, "informationCategory");
        Objects.requireNonNull(analysisSettings, "analysisSettings");
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(content, "content");
        requireNonBlankArgument(contentType, "contentType");
        Objects.requireNonNull(publishedAt, "publishedAt");
        traceparent.ifPresent(value -> requireNonBlankArgument(value, "traceparent"));
        externalId.ifPresent(value -> requireNonBlankArgument(value, "externalId"));
        title.ifPresent(value -> requireNonBlankArgument(value, "title"));
        validateUrl(url);
    }

    private static void validateUrl(URI url) {
        if (!url.isAbsolute()) {
            throw new IllegalArgumentException("url must be absolute");
        }
        String scheme = url.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("url scheme must be HTTP or HTTPS");
        }
        if (url.getHost() == null || url.getHost().isBlank()) {
            throw new IllegalArgumentException("url must contain a host");
        }
        if (url.getUserInfo() != null) {
            throw new IllegalArgumentException("url must not contain embedded credentials");
        }
        if (url.getFragment() != null) {
            throw new IllegalArgumentException("url must not contain a fragment");
        }
    }

}
