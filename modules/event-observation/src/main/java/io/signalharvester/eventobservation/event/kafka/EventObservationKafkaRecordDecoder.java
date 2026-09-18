package io.signalharvester.eventobservation.event.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import io.signalharvester.eventobservation.model.ObservedEventInput;
import io.signalharvester.events.analysis.v1.ItemAnalyzed;
import io.signalharvester.events.analysis.v1.ItemRejected;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import jakarta.inject.Singleton;
import java.util.Objects;

/** Decodes and validates observed Kafka records before Event Observation persistence. */
@Singleton
public final class EventObservationKafkaRecordDecoder {

    private final EventObservationMapper mapper;

    public EventObservationKafkaRecordDecoder(EventObservationMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Parses and validates one RawItemDiscovered record with its original transport metadata. */
    public ObservedEventInput decodeRaw(
            String key, byte[] payload, String topic, int partition, long offset) {
        Objects.requireNonNull(payload, "payload");
        RawItemDiscovered event = parseRaw(payload);
        requireKey(key, event.getRawItemId(), "RawItemDiscovered.raw_item_id");
        return mapper.mapRaw(event, key, topic, partition, offset);
    }

    /** Parses and validates one ItemAnalyzed record with its original transport metadata. */
    public ObservedEventInput decodeAnalyzed(
            String key, byte[] payload, String topic, int partition, long offset) {
        Objects.requireNonNull(payload, "payload");
        ItemAnalyzed event = parseAnalyzed(payload);
        requireKey(key, event.getNormalizedItemId(), "ItemAnalyzed.normalized_item_id");
        return mapper.mapAnalyzed(event, key, topic, partition, offset);
    }

    /** Parses and validates one ItemRejected record with its original transport metadata. */
    public ObservedEventInput decodeRejected(
            String key, byte[] payload, String topic, int partition, long offset) {
        Objects.requireNonNull(payload, "payload");
        ItemRejected event = parseRejected(payload);
        String expectedKey = event.hasNormalizedItemId() ? event.getNormalizedItemId() : event.getRawItemId();
        requireKey(key, expectedKey, "ItemRejected identity");
        return mapper.mapRejected(event, key, topic, partition, offset);
    }

    private static RawItemDiscovered parseRaw(byte[] payload) {
        try {
            return RawItemDiscovered.parseFrom(payload);
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalArgumentException("Invalid RawItemDiscovered Protobuf payload", exception);
        }
    }

    private static ItemAnalyzed parseAnalyzed(byte[] payload) {
        try {
            return ItemAnalyzed.parseFrom(payload);
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalArgumentException("Invalid ItemAnalyzed Protobuf payload", exception);
        }
    }

    private static ItemRejected parseRejected(byte[] payload) {
        try {
            return ItemRejected.parseFrom(payload);
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalArgumentException("Invalid ItemRejected Protobuf payload", exception);
        }
    }

    private static void requireKey(String actualKey, String expectedKey, String identityDescription) {
        if (actualKey == null || !actualKey.equals(expectedKey)) {
            throw new IllegalArgumentException("Kafka key must match " + identityDescription);
        }
    }
}
