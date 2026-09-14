package io.signalharvester.eventobservation.event.kafka;

import com.google.protobuf.Timestamp;
import io.signalharvester.eventobservation.model.ObservedEventInput;
import io.signalharvester.events.analysis.v1.ItemAnalyzed;
import io.signalharvester.events.analysis.v1.ItemRejected;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import io.signalharvester.events.common.v1.EventEnvelope;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;

/** Decodes supported Protobuf integration events into the observation-owned diagnostic model. */
@Singleton
public final class EventObservationMapper {

    private final Supplier<Instant> now;

    public EventObservationMapper() {
        this(Instant::now);
    }

    EventObservationMapper(Supplier<Instant> now) {
        this.now = Objects.requireNonNull(now, "now");
    }

    /** Maps one raw-item event with its Kafka transport metadata. */
    ObservedEventInput mapRaw(
            RawItemDiscovered event, String key, String topic, int partition, long offset) {
        Objects.requireNonNull(event, "event");
        return base(
                event.getEnvelope(), key, topic, partition, offset, "RawItemDiscovered",
                Optional.empty(),
                Optional.of(event.getRawItemId()),
                Optional.empty(),
                Optional.of(event.getSourceId()),
                Optional.of(event.getMonitoringProfileId()),
                Optional.of(event.getInformationCategory()),
                event.hasExternalId() ? Optional.of(event.getExternalId()) : Optional.empty(),
                event.hasTitle() ? Optional.of(event.getTitle()) : Optional.empty(),
                Optional.of(event.getUrl()),
                Optional.of(event.getContentType()),
                Optional.empty(), Optional.empty(), OptionalInt.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Maps one analyzed-item event with its Kafka transport metadata. */
    ObservedEventInput mapAnalyzed(
            ItemAnalyzed event, String key, String topic, int partition, long offset) {
        Objects.requireNonNull(event, "event");
        return base(
                event.getEnvelope(), key, topic, partition, offset, "ItemAnalyzed",
                Optional.of(event.getSourceEventId()),
                Optional.of(event.getRawItemId()),
                Optional.of(event.getNormalizedItemId()),
                Optional.of(event.getSourceId()),
                Optional.of(event.getMonitoringProfileId()),
                Optional.of(event.getInformationCategory()),
                event.hasExternalId() ? Optional.of(event.getExternalId()) : Optional.empty(),
                event.hasTitle() ? Optional.of(event.getTitle()) : Optional.empty(),
                Optional.of(event.getUrl()),
                Optional.of(event.getContentType()),
                Optional.of(event.getRelevant()),
                Optional.of(event.getClassification()),
                OptionalInt.of(event.getScore()),
                Optional.of(event.getAnalyzer()),
                Optional.empty(),
                Optional.of(event.getExplanation()));
    }

    /** Maps one rejected-item event with its Kafka transport metadata. */
    ObservedEventInput mapRejected(
            ItemRejected event, String key, String topic, int partition, long offset) {
        Objects.requireNonNull(event, "event");
        return base(
                event.getEnvelope(), key, topic, partition, offset, "ItemRejected",
                Optional.of(event.getSourceEventId()),
                Optional.of(event.getRawItemId()),
                event.hasNormalizedItemId() ? Optional.of(event.getNormalizedItemId()) : Optional.empty(),
                Optional.of(event.getSourceId()),
                Optional.of(event.getMonitoringProfileId()),
                Optional.of(event.getInformationCategory()),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                OptionalInt.empty(), Optional.empty(), Optional.of(event.getReasonCode()), Optional.of(event.getExplanation()));
    }

    private ObservedEventInput base(
            EventEnvelope envelope,
            String key,
            String topic,
            int partition,
            long offset,
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
        Objects.requireNonNull(envelope, "envelope");
        return new ObservedEventInput(
                envelope.getEventId(),
                envelope.getEventType(),
                instant(envelope.getOccurredAt()),
                Objects.requireNonNull(now.get(), "now supplier returned null"),
                envelope.getCorrelationId(),
                envelope.getTraceparent().isBlank() ? Optional.empty() : Optional.of(envelope.getTraceparent()),
                envelope.getProducer(),
                envelope.getSchemaVersion(),
                topic,
                partition,
                offset,
                key,
                payloadType,
                sourceEventId,
                rawItemId,
                normalizedItemId,
                sourceId,
                monitoringProfileId,
                informationCategory,
                externalId,
                title,
                url,
                contentType,
                relevant,
                classification,
                score,
                analyzer,
                reasonCode,
                explanation);
    }

    private static Instant instant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }
}
