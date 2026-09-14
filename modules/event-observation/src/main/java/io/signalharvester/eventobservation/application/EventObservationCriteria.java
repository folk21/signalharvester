package io.signalharvester.eventobservation.application;

import java.util.Objects;
import java.util.Optional;

/** Filters supported by technical event history and live Event Explorer queries. */
public record EventObservationCriteria(
        Optional<String> eventType,
        Optional<String> producer,
        Optional<String> topic,
        Optional<String> correlationId,
        Optional<String> collectionRunId,
        Optional<String> itemId,
        Optional<String> traceId) {

    public EventObservationCriteria {
        eventType = Objects.requireNonNull(eventType, "eventType");
        producer = Objects.requireNonNull(producer, "producer");
        topic = Objects.requireNonNull(topic, "topic");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        collectionRunId = Objects.requireNonNull(collectionRunId, "collectionRunId");
        itemId = Objects.requireNonNull(itemId, "itemId");
        traceId = Objects.requireNonNull(traceId, "traceId");
    }
}
