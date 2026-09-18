package io.signalharvester.analysis.event.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import io.signalharvester.analysis.model.DiscoveredRawItem;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import jakarta.inject.Singleton;
import java.util.Objects;

/** Decodes and validates one raw-item Kafka record before Analysis application processing. */
@Singleton
public final class RawItemKafkaRecordDecoder {

    private final RawItemDiscoveredMapper mapper;

    public RawItemKafkaRecordDecoder(RawItemDiscoveredMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Parses the original payload, validates its Kafka key, and maps it into the Analysis model. */
    public DiscoveredRawItem decode(String key, byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        RawItemDiscovered event = parse(payload);
        if (key == null || !key.equals(event.getRawItemId())) {
            throw new IllegalArgumentException("Kafka key must match RawItemDiscovered.raw_item_id");
        }
        return mapper.map(event);
    }

    private static RawItemDiscovered parse(byte[] payload) {
        try {
            return RawItemDiscovered.parseFrom(payload);
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalArgumentException("Invalid RawItemDiscovered Protobuf payload", exception);
        }
    }
}
