package io.signalharvester.collection.event;

/**
 * Reports a failed raw-item event publication without leaking Kafka implementation exceptions.
 */
public final class RawItemPublicationException extends RuntimeException {

    private final String rawItemId;
    private final String correlationId;
    private final String topic;

    public RawItemPublicationException(
            String rawItemId,
            String correlationId,
            String topic,
            String message,
            Throwable cause) {
        super(message, cause);
        this.rawItemId = requireNonBlank(rawItemId, "rawItemId");
        this.correlationId = requireNonBlank(correlationId, "correlationId");
        this.topic = requireNonBlank(topic, "topic");
    }

    /**
     * Returns the caller-owned identity of the raw item whose publication failed.
     *
     * @return raw-item identifier
     */
    public String rawItemId() {
        return rawItemId;
    }

    /**
     * Returns the logical flow whose event publication failed.
     *
     * @return correlation identifier supplied by collection orchestration
     */
    public String correlationId() {
        return correlationId;
    }

    /**
     * Returns the configured Kafka topic targeted by the failed publication.
     *
     * @return Kafka topic name
     */
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
