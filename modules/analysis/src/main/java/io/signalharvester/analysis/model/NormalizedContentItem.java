package io.signalharvester.analysis.model;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Common normalized representation consumed by deduplication and analyzers.
 *
 * <p>Category-specific normalized values belong in {@code attributes}; the stable common fields
 * preserve provenance without forcing every information category into one flat schema.</p>
 */
public record NormalizedContentItem(
        String sourceEventId,
        String correlationId,
        Optional<String> traceparent,
        Instant discoveredAt,
        String rawItemId,
        String normalizedItemId,
        String sourceId,
        String monitoringProfileId,
        String informationCategory,
        Optional<String> externalId,
        Optional<String> title,
        URI url,
        String content,
        String contentType,
        Map<String, String> attributes,
        Optional<Instant> publishedAt) {

    public NormalizedContentItem {
        requireNonBlank(sourceEventId, "sourceEventId");
        requireNonBlank(correlationId, "correlationId");
        Objects.requireNonNull(traceparent, "traceparent");
        Objects.requireNonNull(discoveredAt, "discoveredAt");
        requireNonBlank(rawItemId, "rawItemId");
        requireNonBlank(normalizedItemId, "normalizedItemId");
        requireNonBlank(sourceId, "sourceId");
        requireNonBlank(monitoringProfileId, "monitoringProfileId");
        requireNonBlank(informationCategory, "informationCategory");
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(content, "content");
        requireNonBlank(contentType, "contentType");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        Objects.requireNonNull(publishedAt, "publishedAt");
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
