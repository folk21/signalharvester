package io.signalharvester.results.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Results-owned record of one analysis rejection that did not create a user-facing result.
 */
public record RejectedResult(
        String analysisEventId,
        String sourceEventId,
        String rawItemId,
        Optional<String> normalizedItemId,
        String sourceId,
        String monitoringProfileId,
        String informationCategory,
        String reasonCode,
        String explanation,
        Instant rejectedAt,
        String correlationId,
        Optional<String> traceparent) {

    public RejectedResult {
        requireNonBlank(analysisEventId, "analysisEventId");
        requireNonBlank(sourceEventId, "sourceEventId");
        requireNonBlank(rawItemId, "rawItemId");
        normalizedItemId = Objects.requireNonNull(normalizedItemId, "normalizedItemId");
        normalizedItemId.ifPresent(value -> requireHash(value, "normalizedItemId"));
        requireNonBlank(sourceId, "sourceId");
        requireNonBlank(monitoringProfileId, "monitoringProfileId");
        requireNonBlank(informationCategory, "informationCategory");
        requireNonBlank(reasonCode, "reasonCode");
        requireNonBlank(explanation, "explanation");
        rejectedAt = Objects.requireNonNull(rejectedAt, "rejectedAt");
        requireNonBlank(correlationId, "correlationId");
        traceparent = Objects.requireNonNull(traceparent, "traceparent");
        traceparent.ifPresent(value -> requireNonBlank(value, "traceparent"));
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
