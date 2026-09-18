package io.signalharvester.events.analysis.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Timestamp;
import io.signalharvester.events.common.v1.EventEnvelope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies Protobuf round trips and forward-compatible decoding for {@link ItemAnalyzed} and
 * {@link ItemRejected}, including preservation of shared event-envelope metadata.
 *
 * <p>Related specification: {@code backend-event-contracts}.</p>
 *
 * <p>Feature: {@code CONTRACTS.KAFKA_PROTOBUF}.</p>
 */
class AnalysisEventSerializationTest {

    private static final int UNKNOWN_FIELD_NUMBER = 1000;
    private static final String UNKNOWN_FIELD_VALUE = "future-field";
    private static final String NORMALIZED_ITEM_ID = "normalized-01";
    private static final String SOURCE_ID = "source-01";
    private static final String PROFILE_ID = "profile-01";
    private static final String CORRELATION_ID = "run-01";
    private static final String TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    /**
     * Round trip analyzed event.
     */
    @Test
    void shouldRoundTripAnalyzedEvent() throws IOException {
        ItemAnalyzed original = analyzedEvent();

        ItemAnalyzed decoded = ItemAnalyzed.parseFrom(original.toByteArray());

        assertEquals(original, decoded);
    }

    /**
     * Round trip rejected event.
     */
    @Test
    void shouldRoundTripRejectedEvent() throws IOException {
        ItemRejected original = rejectedEvent();

        ItemRejected decoded = ItemRejected.parseFrom(original.toByteArray());

        assertEquals(original, decoded);
    }

    /**
     * Tolerate unknown additive fields on analyzed event.
     */
    @Test
    void shouldTolerateUnknownAdditiveFieldsOnAnalyzedEvent() throws IOException {
        ItemAnalyzed original = analyzedEvent();

        ItemAnalyzed decoded = ItemAnalyzed.parseFrom(withUnknownField(original.toByteArray()));

        assertEquals(original.getNormalizedItemId(), decoded.getNormalizedItemId());
        assertEquals(original.getEnvelope().getEventId(), decoded.getEnvelope().getEventId());
        assertTrue(decoded.getUnknownFields().hasField(UNKNOWN_FIELD_NUMBER));
    }

    /**
     * Tolerate unknown additive fields on rejected event.
     */
    @Test
    void shouldTolerateUnknownAdditiveFieldsOnRejectedEvent() throws IOException {
        ItemRejected original = rejectedEvent();

        ItemRejected decoded = ItemRejected.parseFrom(withUnknownField(original.toByteArray()));

        assertEquals(original.getNormalizedItemId(), decoded.getNormalizedItemId());
        assertEquals(original.getEnvelope().getEventId(), decoded.getEnvelope().getEventId());
        assertTrue(decoded.getUnknownFields().hasField(UNKNOWN_FIELD_NUMBER));
    }

    private static ItemAnalyzed analyzedEvent() {
        Timestamp timestamp = Timestamp.newBuilder().setSeconds(1_725_817_600L).build();
        return ItemAnalyzed.newBuilder()
                .setEnvelope(envelope("analysis.item-analyzed.v1", "analysis-event-01", timestamp))
                .setSourceEventId("raw-event-01")
                .setRawItemId("raw-01")
                .setNormalizedItemId(NORMALIZED_ITEM_ID)
                .setSourceId(SOURCE_ID)
                .setMonitoringProfileId(PROFILE_ID)
                .setInformationCategory("JOB")
                .setExternalId("external-42")
                .setTitle("Senior Java Backend Engineer")
                .setUrl("https://example.test/jobs/42")
                .setNormalizedContent("Java Kafka PostgreSQL")
                .setContentType("text/plain")
                .putAllAttributes(Map.of("language", "java"))
                .setRelevant(true)
                .setClassification("MATCHED_KEYWORDS")
                .setScore(67)
                .addTags("java")
                .addTags("kafka")
                .setExplanation("Matched 2 of 3 configured keywords: java, kafka")
                .setAnalyzer("keyword-v1")
                .setPublishedAt(timestamp)
                .build();
    }

    private static ItemRejected rejectedEvent() {
        Timestamp timestamp = Timestamp.newBuilder().setSeconds(1_725_817_601L).build();
        return ItemRejected.newBuilder()
                .setEnvelope(envelope("analysis.item-rejected.v1", "rejection-event-01", timestamp))
                .setSourceEventId("raw-event-02")
                .setRawItemId("raw-02")
                .setNormalizedItemId(NORMALIZED_ITEM_ID)
                .setSourceId(SOURCE_ID)
                .setMonitoringProfileId(PROFILE_ID)
                .setInformationCategory("JOB")
                .setReasonCode("DUPLICATE")
                .setExplanation("Logical item was already accepted for this monitoring profile")
                .build();
    }

    private static EventEnvelope envelope(String eventType, String eventId, Timestamp timestamp) {
        return EventEnvelope.newBuilder()
                .setEventId(eventId)
                .setEventType(eventType)
                .setOccurredAt(timestamp)
                .setCorrelationId(CORRELATION_ID)
                .setTraceparent(TRACEPARENT)
                .setProducer("analysis")
                .setSchemaVersion("v1")
                .build();
    }

    private static byte[] withUnknownField(byte[] message) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(message);
        CodedOutputStream codedOutput = CodedOutputStream.newInstance(bytes);
        codedOutput.writeString(UNKNOWN_FIELD_NUMBER, UNKNOWN_FIELD_VALUE);
        codedOutput.flush();
        return bytes.toByteArray();
    }
}
