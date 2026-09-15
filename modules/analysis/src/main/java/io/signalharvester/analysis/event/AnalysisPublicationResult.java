package io.signalharvester.analysis.event;

/**
 * Identifies a terminal Analysis event staged for reliable Kafka publication without exposing Protobuf types.
 */
public record AnalysisPublicationResult(String eventId, String topic, String normalizedItemId) {

    public AnalysisPublicationResult {
        requireNonBlank(eventId, "eventId");
        requireNonBlank(topic, "topic");
        requireNonBlank(normalizedItemId, "normalizedItemId");
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
