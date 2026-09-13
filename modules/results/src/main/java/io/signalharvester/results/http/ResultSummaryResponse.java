package io.signalharvester.results.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.results.application.ResultSummary;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** REST representation of one compact analyzed result in the result feed. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable
public record ResultSummaryResponse(
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

    static ResultSummaryResponse from(ResultSummary result) {
        return new ResultSummaryResponse(
                result.monitoringProfileId(),
                result.normalizedItemId(),
                result.sourceId(),
                result.informationCategory(),
                result.externalId(),
                result.title(),
                result.url(),
                result.relevant(),
                result.classification(),
                result.score(),
                result.attributes(),
                result.tags(),
                result.explanation(),
                result.analyzer(),
                result.publishedAt(),
                result.analyzedAt());
    }
}
