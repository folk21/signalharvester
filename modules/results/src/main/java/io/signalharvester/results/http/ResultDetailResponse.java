package io.signalharvester.results.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.results.application.ResultDetail;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** REST representation of one detailed materialized analyzed result. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable
public record ResultDetailResponse(
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

    static ResultDetailResponse from(ResultDetail result) {
        return new ResultDetailResponse(
                result.monitoringProfileId(),
                result.normalizedItemId(),
                result.analysisEventId(),
                result.sourceEventId(),
                result.rawItemId(),
                result.sourceId(),
                result.informationCategory(),
                result.externalId(),
                result.title(),
                result.url(),
                result.normalizedContent(),
                result.contentType(),
                result.attributes(),
                result.relevant(),
                result.classification(),
                result.score(),
                result.tags(),
                result.explanation(),
                result.analyzer(),
                result.publishedAt(),
                result.analyzedAt(),
                result.correlationId(),
                result.traceparent());
    }
}
