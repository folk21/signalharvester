package io.signalharvester.results.model;

import static io.signalharvester.common.validation.Preconditions.requireNonBlankArgument;
import static io.signalharvester.common.validation.Preconditions.requireOptionalNonBlank;
import static io.signalharvester.common.validation.Preconditions.requireRange;

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
        requireNonBlankArgument(analysisEventId, "analysisEventId");
        requireNonBlankArgument(sourceEventId, "sourceEventId");
        requireNonBlankArgument(rawItemId, "rawItemId");
        requireHash(normalizedItemId, "normalizedItemId");
        requireNonBlankArgument(sourceId, "sourceId");
        requireNonBlankArgument(monitoringProfileId, "monitoringProfileId");
        requireNonBlankArgument(informationCategory, "informationCategory");
        externalId = requireOptionalNonBlank(externalId, "externalId");
        title = requireOptionalNonBlank(title, "title");
        requireNonBlankArgument(url, "url");
        Objects.requireNonNull(normalizedContent, "normalizedContent");
        requireNonBlankArgument(contentType, "contentType");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        requireNonBlankArgument(classification, "classification");
        requireRange(score, 0, 100, "score");
        tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
        requireNonBlankArgument(explanation, "explanation");
        requireNonBlankArgument(analyzer, "analyzer");
        publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
        analyzedAt = Objects.requireNonNull(analyzedAt, "analyzedAt");
        requireNonBlankArgument(correlationId, "correlationId");
        traceparent = requireOptionalNonBlank(traceparent, "traceparent");
        attributes.forEach((key, value) -> {
            requireNonBlankArgument(key, "attribute key");
            Objects.requireNonNull(value, "attribute value");
        });
        tags.forEach(tag -> requireNonBlankArgument(tag, "tag"));
    }


    private static void requireHash(String value, String name) {
        requireNonBlankArgument(value, name);
        if (value.length() != 64) {
            throw new IllegalArgumentException(name + " must contain 64 characters");
        }
    }

}
