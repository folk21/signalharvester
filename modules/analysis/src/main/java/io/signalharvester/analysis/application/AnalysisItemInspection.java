package io.signalharvester.analysis.application;

import java.time.Instant;
import java.util.Optional;

/** Read-only operational projection of one durable normalized-item deduplication claim. */
public record AnalysisItemInspection(
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
}
