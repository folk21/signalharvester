package io.signalharvester.analysis.outbox;

import java.time.Instant;
import java.util.Objects;

/** Immutable serialized Analysis event awaiting Kafka publication. */
public record AnalysisOutboxEntry(
        String eventId,
        String topic,
        String eventKey,
        byte[] payload,
        Instant createdAt,
        int publicationAttempts) {

    public AnalysisOutboxEntry {
        eventId = requireText(eventId, "eventId");
        topic = requireText(topic, "topic");
        eventKey = requireText(eventKey, "eventKey");
        payload = Objects.requireNonNull(payload, "payload").clone();
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (publicationAttempts < 0) {
            throw new IllegalArgumentException("publicationAttempts must not be negative");
        }
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
