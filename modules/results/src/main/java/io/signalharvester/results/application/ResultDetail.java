package io.signalharvester.results.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Detailed read model for one materialized analyzed result including content and provenance.
 */
public record ResultDetail(
        String monitoringProfileId,
        String normalizedItemId,
        String analysisEventId,
        String sourceEventId,
        String rawItemId,
        String sourceId,
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

    public ResultDetail {
        attributes = Map.copyOf(attributes);
        tags = List.copyOf(tags);
    }
}
