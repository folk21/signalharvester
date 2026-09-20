package io.signalharvester.analysis.application;

import static io.signalharvester.common.validation.Preconditions.requireNonBlankArgument;

/**
 * Identifies the terminal event staged after processing one raw discovery.
 */
public record RawItemProcessingResult(
        RawItemProcessingStatus status,
        String normalizedItemId,
        String eventId,
        String topic) {

    public RawItemProcessingResult {
        if (status == null) {
            throw new NullPointerException("status");
        }
        requireNonBlankArgument(normalizedItemId, "normalizedItemId");
        requireNonBlankArgument(eventId, "eventId");
        requireNonBlankArgument(topic, "topic");
    }

}
