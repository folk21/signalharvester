package io.signalharvester.eventobservation.model;

import static io.signalharvester.common.validation.Preconditions.requireNonBlank;
import static io.signalharvester.common.validation.Preconditions.requireNonNegative;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Decoded diagnostic representation of one published Kafka event before persistence assigns its cursor. */
public record ObservedEventInput(
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

    public ObservedEventInput {
        eventId = requireNonBlank(eventId, "eventId");
        eventType = requireNonBlank(eventType, "eventType");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        correlationId = requireNonBlank(correlationId, "correlationId");
        traceparent = Objects.requireNonNull(traceparent, "traceparent");
        producer = requireNonBlank(producer, "producer");
        schemaVersion = requireNonBlank(schemaVersion, "schemaVersion");
        kafkaTopic = requireNonBlank(kafkaTopic, "kafkaTopic");
        requireNonNegative(kafkaPartition, "kafkaPartition");
        requireNonNegative(kafkaOffset, "kafkaOffset");
        kafkaKey = requireNonBlank(kafkaKey, "kafkaKey");
        payloadType = requireNonBlank(payloadType, "payloadType");
        sourceEventId = Objects.requireNonNull(sourceEventId, "sourceEventId");
        rawItemId = Objects.requireNonNull(rawItemId, "rawItemId");
        normalizedItemId = Objects.requireNonNull(normalizedItemId, "normalizedItemId");
        sourceId = Objects.requireNonNull(sourceId, "sourceId");
        monitoringProfileId = Objects.requireNonNull(monitoringProfileId, "monitoringProfileId");
        informationCategory = Objects.requireNonNull(informationCategory, "informationCategory");
        externalId = Objects.requireNonNull(externalId, "externalId");
        title = Objects.requireNonNull(title, "title");
        url = Objects.requireNonNull(url, "url");
        contentType = Objects.requireNonNull(contentType, "contentType");
        relevant = Objects.requireNonNull(relevant, "relevant");
        classification = Objects.requireNonNull(classification, "classification");
        score = Objects.requireNonNull(score, "score");
        analyzer = Objects.requireNonNull(analyzer, "analyzer");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        explanation = Objects.requireNonNull(explanation, "explanation");
    }

}
