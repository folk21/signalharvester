package io.signalharvester.eventobservation.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.eventobservation.model.ObservedEvent;
import java.time.Instant;

/** Browser-facing diagnostic representation of one observed application event. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable
public record ObservedEventResponse(
        long cursor,
        String eventId,
        String eventType,
        Instant occurredAt,
        Instant observedAt,
        String correlationId,
        @Nullable String traceparent,
        String producer,
        String schemaVersion,
        ObservedKafkaMetadataResponse kafka,
        ObservedPayloadResponse payload) {

    static ObservedEventResponse from(ObservedEvent event) {
        return new ObservedEventResponse(
                event.observationId(),
                event.eventId(),
                event.eventType(),
                event.occurredAt(),
                event.observedAt(),
                event.correlationId(),
                event.traceparent().orElse(null),
                event.producer(),
                event.schemaVersion(),
                new ObservedKafkaMetadataResponse(
                        event.kafkaTopic(), event.kafkaPartition(), event.kafkaOffset(), event.kafkaKey()),
                new ObservedPayloadResponse(
                        event.payloadType(),
                        event.sourceEventId().orElse(null),
                        event.rawItemId().orElse(null),
                        event.normalizedItemId().orElse(null),
                        event.sourceId().orElse(null),
                        event.monitoringProfileId().orElse(null),
                        event.informationCategory().orElse(null),
                        event.externalId().orElse(null),
                        event.title().orElse(null),
                        event.url().orElse(null),
                        event.contentType().orElse(null),
                        event.relevant().orElse(null),
                        event.classification().orElse(null),
                        event.score().isPresent() ? event.score().getAsInt() : null,
                        event.analyzer().orElse(null),
                        event.reasonCode().orElse(null),
                        event.explanation().orElse(null)));
    }
}
