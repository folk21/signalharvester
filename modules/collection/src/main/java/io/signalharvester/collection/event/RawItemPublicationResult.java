package io.signalharvester.collection.event;

import static io.signalharvester.common.validation.Preconditions.requireNonBlankArgument;

/**
 * Identifies a successfully acknowledged raw-item Kafka publication without exposing Protobuf types.
 *
 * @param eventId globally unique event identifier
 * @param rawItemId caller-owned stable identity of the published raw item
 * @param topic Kafka topic that acknowledged the record
 */
public record RawItemPublicationResult(String eventId, String rawItemId, String topic) {

    public RawItemPublicationResult {
        requireNonBlankArgument(eventId, "eventId");
        requireNonBlankArgument(rawItemId, "rawItemId");
        requireNonBlankArgument(topic, "topic");
    }

}
