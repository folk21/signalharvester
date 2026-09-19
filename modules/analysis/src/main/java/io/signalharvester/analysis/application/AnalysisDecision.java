package io.signalharvester.analysis.application;

import static io.signalharvester.common.validation.Preconditions.requireNonBlankArgument;
import static io.signalharvester.common.validation.Preconditions.requireRange;

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
        requireNonBlankArgument(classification, "classification");
        requireRange(score, 0, 100, "score");
        tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
        requireNonBlankArgument(explanation, "explanation");
        requireNonBlankArgument(analyzer, "analyzer");
    }

}
