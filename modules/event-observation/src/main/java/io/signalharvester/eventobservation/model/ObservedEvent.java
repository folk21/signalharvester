package io.signalharvester.eventobservation.model;

import static io.signalharvester.common.validation.Preconditions.requirePositive;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Persisted technical event projection exposed to Event Explorer clients. */
public record ObservedEvent(
        long observationId,
        String eventId,
        String eventType,
        Instant occurredAt,
        Instant observedAt,
        String correlationId,
        Optional<String> traceparent,
        String producer,
        String schemaVersion,
        String kafkaTopic,
        int kafkaPartition,
        long kafkaOffset,
        String kafkaKey,
        String payloadType,
        Optional<String> sourceEventId,
        Optional<String> rawItemId,
        Optional<String> normalizedItemId,
        Optional<String> sourceId,
        Optional<String> monitoringProfileId,
        Optional<String> informationCategory,
        Optional<String> externalId,
        Optional<String> title,
        Optional<String> url,
        Optional<String> contentType,
        Optional<Boolean> relevant,
        Optional<String> classification,
        OptionalInt score,
        Optional<String> analyzer,
        Optional<String> reasonCode,
        Optional<String> explanation) {

    public ObservedEvent {
        requirePositive(observationId, "observationId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(traceparent, "traceparent");
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(kafkaTopic, "kafkaTopic");
        Objects.requireNonNull(kafkaKey, "kafkaKey");
        Objects.requireNonNull(payloadType, "payloadType");
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        Objects.requireNonNull(rawItemId, "rawItemId");
        Objects.requireNonNull(normalizedItemId, "normalizedItemId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(monitoringProfileId, "monitoringProfileId");
        Objects.requireNonNull(informationCategory, "informationCategory");
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(relevant, "relevant");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(analyzer, "analyzer");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(explanation, "explanation");
    }
}
