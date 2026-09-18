package io.signalharvester.events.collection.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Timestamp;
import io.signalharvester.events.common.v1.EventEnvelope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Verifies Protobuf round trips and forward-compatible decoding for {@link RawItemDiscovered}, including
 * preservation of identifiers, provenance, attributes, and shared event-envelope metadata.
 *
 * <p>Related specification: {@code backend-event-contracts}.</p>
 *
 * <p>Features: {@code CONTRACTS.KAFKA_PROTOBUF}, {@code EVENTING.CORRELATION}.</p>
 */
class RawItemDiscoveredSerializationTest {

    private static final int UNKNOWN_FIELD_NUMBER = 1000;
    private static final String UNKNOWN_FIELD_VALUE = "future-field";
    private static final String EVENT_ID = "event-01";
    private static final String RUN_ID = "run-01";
    private static final String RAW_ITEM_ID = "raw-01";
    private static final String SOURCE_ID = "source-01";
    private static final String PROFILE_ID = "profile-01";
    private static final String TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    /**
     * Round trip representative event.
     */
    @Test
    void shouldRoundTripRepresentativeEvent() throws IOException {
        RawItemDiscovered original = representativeEvent();

        RawItemDiscovered decoded = RawItemDiscovered.parseFrom(original.toByteArray());

        assertEquals(original, decoded);
        assertTrue(decoded.hasAnalysisSettings());
        assertEquals(java.util.List.of("java", "kafka"), decoded.getAnalysisSettings().getKeywordsList());
        assertEquals(1, decoded.getAnalysisSettings().getMinimumMatches());
    }

    /**
     * Tolerate unknown additive fields.
     */
    @Test
    void shouldTolerateUnknownAdditiveFields() throws IOException {
        RawItemDiscovered original = representativeEvent();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(original.toByteArray());

        CodedOutputStream codedOutput = CodedOutputStream.newInstance(bytes);
        codedOutput.writeString(UNKNOWN_FIELD_NUMBER, UNKNOWN_FIELD_VALUE);
        codedOutput.flush();

        RawItemDiscovered decoded = RawItemDiscovered.parseFrom(bytes.toByteArray());

        assertEquals(original.getRawItemId(), decoded.getRawItemId());
        assertEquals(original.getEnvelope().getEventId(), decoded.getEnvelope().getEventId());
        assertTrue(decoded.getUnknownFields().hasField(UNKNOWN_FIELD_NUMBER));
    }

    /** Legacy payloads without settings remain decodable with field presence absent. */
    @Test
    void shouldDecodeLegacyEventWithoutAnalysisSettings() throws IOException {
        RawItemDiscovered legacy = representativeEvent().toBuilder().clearAnalysisSettings().build();

        RawItemDiscovered decoded = RawItemDiscovered.parseFrom(legacy.toByteArray());

        assertFalse(decoded.hasAnalysisSettings());
        assertEquals(RAW_ITEM_ID, decoded.getRawItemId());
    }

    private static RawItemDiscovered representativeEvent() {
        Timestamp occurredAt = Timestamp.newBuilder()
                .setSeconds(1_725_817_600L)
                .build();

        EventEnvelope envelope = EventEnvelope.newBuilder()
                .setEventId(EVENT_ID)
                .setEventType("collection.raw-item-discovered.v1")
                .setOccurredAt(occurredAt)
                .setCorrelationId(RUN_ID)
                .setTraceparent(TRACEPARENT)
                .setProducer("collection")
                .setSchemaVersion("v1")
                .build();

        return RawItemDiscovered.newBuilder()
                .setEnvelope(envelope)
                .setRawItemId(RAW_ITEM_ID)
                .setSourceId(SOURCE_ID)
                .setMonitoringProfileId(PROFILE_ID)
                .setInformationCategory("JOB")
                .setExternalId("external-42")
                .setTitle("Senior Java Backend Engineer")
                .setUrl("https://example.test/jobs/42")
                .setContent("Java, Kafka and PostgreSQL")
                .setContentType("text/plain")
                .setPublishedAt(occurredAt)
                .setAnalysisSettings(KeywordAnalysisSettings.newBuilder()
                        .addAllKeywords(java.util.List.of("java", "kafka"))
                        .setMinimumMatches(1))
                .build();
    }
}
