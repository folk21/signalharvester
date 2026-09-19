package io.signalharvester.results.model;

import static io.signalharvester.common.validation.Preconditions.requireNonBlankArgument;
import static io.signalharvester.common.validation.Preconditions.requireOptionalNonBlank;

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
        requireNonBlankArgument(analysisEventId, "analysisEventId");
        requireNonBlankArgument(sourceEventId, "sourceEventId");
        requireNonBlankArgument(rawItemId, "rawItemId");
        normalizedItemId = Objects.requireNonNull(normalizedItemId, "normalizedItemId");
        normalizedItemId.ifPresent(value -> requireHash(value, "normalizedItemId"));
        requireNonBlankArgument(sourceId, "sourceId");
        requireNonBlankArgument(monitoringProfileId, "monitoringProfileId");
        requireNonBlankArgument(informationCategory, "informationCategory");
        requireNonBlankArgument(reasonCode, "reasonCode");
        requireNonBlankArgument(explanation, "explanation");
        rejectedAt = Objects.requireNonNull(rejectedAt, "rejectedAt");
        requireNonBlankArgument(correlationId, "correlationId");
        traceparent = requireOptionalNonBlank(traceparent, "traceparent");
    }

    private static void requireHash(String value, String name) {
        requireNonBlankArgument(value, name);
        if (value.length() != 64) {
            throw new IllegalArgumentException(name + " must contain 64 characters");
        }
    }

}
