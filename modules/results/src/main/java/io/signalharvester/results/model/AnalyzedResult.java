package io.signalharvester.results.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Results-owned projection payload for one successfully analyzed logical item.
 */
public record AnalyzedResult(
        String analysisEventId,
        String sourceEventId,
        String rawItemId,
        String normalizedItemId,
        String sourceId,
        String monitoringProfileId,
        String informationCategory,
        Optional<String> externalId,
        Optional<String> title,
        String url,
        String normalizedContent,
        String contentType,
        Map<String, String> attributes,
        boolean relevant,
        String classification,
        int score,
        List<String> tags,
        String explanation,
        String analyzer,
        Optional<Instant> publishedAt,
        Instant analyzedAt,
        String correlationId,
        Optional<String> traceparent) {

    public AnalyzedResult {
        requireNonBlank(analysisEventId, "analysisEventId");
        requireNonBlank(sourceEventId, "sourceEventId");
        requireNonBlank(rawItemId, "rawItemId");
        requireHash(normalizedItemId, "normalizedItemId");
        requireNonBlank(sourceId, "sourceId");
        requireNonBlank(monitoringProfileId, "monitoringProfileId");
        requireNonBlank(informationCategory, "informationCategory");
        externalId = requireOptional(externalId, "externalId");
        title = requireOptional(title, "title");
        requireNonBlank(url, "url");
        Objects.requireNonNull(normalizedContent, "normalizedContent");
        requireNonBlank(contentType, "contentType");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        requireNonBlank(classification, "classification");
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be between 0 and 100");
        }
        tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
        requireNonBlank(explanation, "explanation");
        requireNonBlank(analyzer, "analyzer");
        publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
        analyzedAt = Objects.requireNonNull(analyzedAt, "analyzedAt");
        requireNonBlank(correlationId, "correlationId");
        traceparent = requireOptional(traceparent, "traceparent");
        attributes.forEach((key, value) -> {
            requireNonBlank(key, "attribute key");
            Objects.requireNonNull(value, "attribute value");
        });
        tags.forEach(tag -> requireNonBlank(tag, "tag"));
    }

    private static Optional<String> requireOptional(Optional<String> value, String name) {
        Objects.requireNonNull(value, name);
        value.ifPresent(item -> requireNonBlank(item, name));
        return value;
    }

    private static void requireHash(String value, String name) {
        requireNonBlank(value, name);
        if (value.length() != 64) {
            throw new IllegalArgumentException(name + " must contain 64 characters");
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
