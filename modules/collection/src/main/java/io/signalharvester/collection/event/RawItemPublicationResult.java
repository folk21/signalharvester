package io.signalharvester.collection.event;

/**
 * Identifies a successfully acknowledged raw-item Kafka publication without exposing Protobuf types.
 *
 * @param eventId globally unique event identifier
 * @param rawItemId caller-owned stable identity of the published raw item
 * @param topic Kafka topic that acknowledged the record
 */
public record RawItemPublicationResult(String eventId, String rawItemId, String topic) {

    public RawItemPublicationResult {
        requireNonBlank(eventId, "eventId");
        requireNonBlank(rawItemId, "rawItemId");
        requireNonBlank(topic, "topic");
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
