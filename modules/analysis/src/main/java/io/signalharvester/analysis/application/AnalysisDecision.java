package io.signalharvester.analysis.application;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic analyzer outcome independent of Kafka and persistence transport types.
 */
public record AnalysisDecision(
        boolean relevant,
        String classification,
        int score,
        List<String> tags,
        String explanation,
        String analyzer) {

    public AnalysisDecision {
        requireNonBlank(classification, "classification");
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be between 0 and 100");
        }
        tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
        requireNonBlank(explanation, "explanation");
        requireNonBlank(analyzer, "analyzer");
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
