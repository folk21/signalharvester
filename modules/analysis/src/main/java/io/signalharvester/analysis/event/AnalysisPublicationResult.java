package io.signalharvester.analysis.event;

import static io.signalharvester.common.validation.Preconditions.requireNonBlankArgument;

/**
 * Identifies a terminal Analysis event staged for reliable Kafka publication without exposing Protobuf types.
 */
public record AnalysisPublicationResult(String eventId, String topic, String normalizedItemId) {

    public AnalysisPublicationResult {
        requireNonBlankArgument(eventId, "eventId");
        requireNonBlankArgument(topic, "topic");
        requireNonBlankArgument(normalizedItemId, "normalizedItemId");
    }

}
