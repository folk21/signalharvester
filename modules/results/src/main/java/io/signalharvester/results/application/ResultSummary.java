package io.signalharvester.results.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Compact user-facing projection used by bounded result-feed queries.
 */
public record ResultSummary(
        String monitoringProfileId,
        String normalizedItemId,
        String sourceId,
        String informationCategory,
        Optional<String> externalId,
        Optional<String> title,
        String url,
        boolean relevant,
        String classification,
        int score,
        Map<String, String> attributes,
        List<String> tags,
        String explanation,
        String analyzer,
        Optional<Instant> publishedAt,
        Instant analyzedAt) {

    public ResultSummary {
        attributes = Map.copyOf(attributes);
        tags = List.copyOf(tags);
    }
}
