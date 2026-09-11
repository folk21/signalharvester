package io.signalharvester.analysis.event;

/**
 * Normalizes failed analysis event publication without leaking Kafka implementation exceptions.
 */
public final class AnalysisPublicationException extends RuntimeException {

    private final String normalizedItemId;
    private final String topic;

    public AnalysisPublicationException(String normalizedItemId, String topic, String message, Throwable cause) {
        super(message, cause);
        this.normalizedItemId = requireNonBlank(normalizedItemId, "normalizedItemId");
        this.topic = requireNonBlank(topic, "topic");
    }

    public String normalizedItemId() {
        return normalizedItemId;
    }

    public String topic() {
        return topic;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
