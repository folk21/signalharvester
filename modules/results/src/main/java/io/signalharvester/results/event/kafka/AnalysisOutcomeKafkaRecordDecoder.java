package io.signalharvester.results.event.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import io.signalharvester.events.analysis.v1.ItemAnalyzed;
import io.signalharvester.events.analysis.v1.ItemRejected;
import io.signalharvester.results.model.AnalyzedResult;
import io.signalharvester.results.model.RejectedResult;
import jakarta.inject.Singleton;
import java.util.Objects;

/** Decodes and validates terminal Analysis Kafka records before Results projection. */
@Singleton
public final class AnalysisOutcomeKafkaRecordDecoder {

    private final AnalysisOutcomeMapper mapper;

    public AnalysisOutcomeKafkaRecordDecoder(AnalysisOutcomeMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Parses and validates one ItemAnalyzed record. */
    public AnalyzedResult decodeAnalyzed(String key, byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        ItemAnalyzed event = parseAnalyzed(payload);
        requireKey(key, event.getNormalizedItemId(), "ItemAnalyzed.normalized_item_id");
        return mapper.map(event);
    }

    /** Parses and validates one ItemRejected record. */
    public RejectedResult decodeRejected(String key, byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        ItemRejected event = parseRejected(payload);
        String expectedKey = event.hasNormalizedItemId() ? event.getNormalizedItemId() : event.getRawItemId();
        requireKey(key, expectedKey, "ItemRejected identity");
        return mapper.map(event);
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
