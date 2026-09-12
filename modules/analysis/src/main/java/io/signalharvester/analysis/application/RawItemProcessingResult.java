package io.signalharvester.analysis.application;

/**
 * Identifies the terminal event emitted after processing one raw discovery.
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
        requireNonBlank(normalizedItemId, "normalizedItemId");
        requireNonBlank(eventId, "eventId");
        requireNonBlank(topic, "topic");
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
