package io.signalharvester.eventobservation.event.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.OffsetStrategy;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Requires;
import io.signalharvester.eventobservation.application.EventObservationRecorder;
import io.signalharvester.events.analysis.v1.ItemAnalyzed;
import io.signalharvester.events.analysis.v1.ItemRejected;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

/** Kafka ingress adapter that records selected published events for the technical Event Explorer. */
@KafkaListener(
        value = "${signalharvester.event-observation.consumer-group:signalharvester-event-observation-v1}",
        offsetReset = OffsetReset.EARLIEST,
        offsetStrategy = OffsetStrategy.DISABLED)
@Requires(property = "signalharvester.event-observation.enabled", value = "true")
public class EventObservationKafkaListener {

    private final EventObservationMapper mapper;
    private final EventObservationRecorder recorder;

    public EventObservationKafkaListener(EventObservationMapper mapper, EventObservationRecorder recorder) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    /** Records a raw-item discovery after validating its Kafka key. */
    @Topic("${signalharvester.kafka.raw-item-discovered-topic:signalharvester.collection.raw-item-discovered.v1}")
    public void receiveRaw(
            @KafkaKey String key, byte[] payload, long offset, int partition, String topic, Consumer<?, ?> consumer) {
        RawItemDiscovered event = parseRaw(payload);
        requireKey(key, event.getRawItemId(), "RawItemDiscovered.raw_item_id");
        recorder.record(mapper.mapRaw(event, key, topic, partition, offset));
        commit(consumer, topic, partition, offset);
    }

    /** Records an analyzed-item event after validating its Kafka key. */
    @Topic("${signalharvester.kafka.item-analyzed-topic:signalharvester.analysis.item-analyzed.v1}")
    public void receiveAnalyzed(
            @KafkaKey String key, byte[] payload, long offset, int partition, String topic, Consumer<?, ?> consumer) {
        ItemAnalyzed event = parseAnalyzed(payload);
        requireKey(key, event.getNormalizedItemId(), "ItemAnalyzed.normalized_item_id");
        recorder.record(mapper.mapAnalyzed(event, key, topic, partition, offset));
        commit(consumer, topic, partition, offset);
    }

    /** Records a rejected-item event after validating its Kafka key. */
    @Topic("${signalharvester.kafka.item-rejected-topic:signalharvester.analysis.item-rejected.v1}")
    public void receiveRejected(
            @KafkaKey String key, byte[] payload, long offset, int partition, String topic, Consumer<?, ?> consumer) {
        ItemRejected event = parseRejected(payload);
        String expectedKey = event.hasNormalizedItemId() ? event.getNormalizedItemId() : event.getRawItemId();
        requireKey(key, expectedKey, "ItemRejected identity");
        recorder.record(mapper.mapRejected(event, key, topic, partition, offset));
        commit(consumer, topic, partition, offset);
    }

    private static RawItemDiscovered parseRaw(byte[] payload) {
        try {
            return RawItemDiscovered.parseFrom(Objects.requireNonNull(payload, "payload"));
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalArgumentException("Invalid RawItemDiscovered Protobuf payload", exception);
        }
    }

    private static ItemAnalyzed parseAnalyzed(byte[] payload) {
        try {
            return ItemAnalyzed.parseFrom(Objects.requireNonNull(payload, "payload"));
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalArgumentException("Invalid ItemAnalyzed Protobuf payload", exception);
        }
    }

    private static ItemRejected parseRejected(byte[] payload) {
        try {
            return ItemRejected.parseFrom(Objects.requireNonNull(payload, "payload"));
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalArgumentException("Invalid ItemRejected Protobuf payload", exception);
        }
    }

    private static void requireKey(String actual, String expected, String description) {
        Objects.requireNonNull(actual, "key");
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("Kafka key must match " + description);
        }
    }

    private static void commit(Consumer<?, ?> consumer, String topic, int partition, long offset) {
        Objects.requireNonNull(consumer, "consumer");
        consumer.commitSync(Map.of(
                new TopicPartition(topic, partition),
                new OffsetAndMetadata(offset + 1)));
    }
}
