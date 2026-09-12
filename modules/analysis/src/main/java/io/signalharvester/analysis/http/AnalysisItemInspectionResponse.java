package io.signalharvester.analysis.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.analysis.api.AnalysisItemInspection;
import java.time.Instant;
import java.util.Optional;

/** REST representation of durable analysis deduplication state. */
@Serdeable
public record AnalysisItemInspectionResponse(
        String monitoringProfileId,
        String normalizedItemId,
        String sourceId,
        Optional<String> externalId,
        String sourceUrl,
        String firstRawItemId,
        String firstSourceEventId,
        Instant firstSeenAt,
        String lastRawItemId,
        String lastSourceEventId,
        Instant lastSeenAt,
        long discoveryCount) {

    static AnalysisItemInspectionResponse from(AnalysisItemInspection item) {
        return new AnalysisItemInspectionResponse(
                item.monitoringProfileId(), item.normalizedItemId(), item.sourceId(), item.externalId(),
                item.sourceUrl(), item.firstRawItemId(), item.firstSourceEventId(), item.firstSeenAt(),
                item.lastRawItemId(), item.lastSourceEventId(), item.lastSeenAt(), item.discoveryCount());
    }
}
