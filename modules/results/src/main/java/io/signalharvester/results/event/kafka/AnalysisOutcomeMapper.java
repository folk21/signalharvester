package io.signalharvester.results.event.kafka;

import com.google.protobuf.Timestamp;
import io.signalharvester.events.analysis.v1.ItemAnalyzed;
import io.signalharvester.events.analysis.v1.ItemRejected;
import io.signalharvester.results.model.AnalyzedResult;
import io.signalharvester.results.model.RejectedResult;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.Optional;

/**
 * Maps version-one terminal analysis Protobuf messages into Results-owned projection models.
 */
@Singleton
public final class AnalysisOutcomeMapper {

    AnalyzedResult map(ItemAnalyzed event) {
        var envelope = event.getEnvelope();
        return new AnalyzedResult(
                envelope.getEventId(),
                event.getSourceEventId(),
                event.getRawItemId(),
                event.getNormalizedItemId(),
                event.getSourceId(),
                event.getMonitoringProfileId(),
                event.getInformationCategory(),
                event.hasExternalId() ? Optional.of(event.getExternalId()) : Optional.empty(),
                event.hasTitle() ? Optional.of(event.getTitle()) : Optional.empty(),
                event.getUrl(),
                event.getNormalizedContent(),
                event.getContentType(),
                event.getAttributesMap(),
                event.getRelevant(),
                event.getClassification(),
                event.getScore(),
                event.getTagsList(),
                event.getExplanation(),
                event.getAnalyzer(),
                event.hasPublishedAt() ? Optional.of(instant(event.getPublishedAt())) : Optional.empty(),
                instant(envelope.getOccurredAt()),
                envelope.getCorrelationId(),
                optional(envelope.getTraceparent()));
    }

    RejectedResult map(ItemRejected event) {
        var envelope = event.getEnvelope();
        return new RejectedResult(
                envelope.getEventId(),
                event.getSourceEventId(),
                event.getRawItemId(),
                event.hasNormalizedItemId() ? Optional.of(event.getNormalizedItemId()) : Optional.empty(),
                event.getSourceId(),
                event.getMonitoringProfileId(),
                event.getInformationCategory(),
                event.getReasonCode(),
                event.getExplanation(),
                instant(envelope.getOccurredAt()),
                envelope.getCorrelationId(),
                optional(envelope.getTraceparent()));
    }

    private static Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static Instant instant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }
}
