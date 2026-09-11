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

class AnalysisEventSerializationTest {

    @Test
    void shouldRoundTripAnalyzedEvent() throws IOException {
        ItemAnalyzed original = analyzedEvent();

        ItemAnalyzed decoded = ItemAnalyzed.parseFrom(original.toByteArray());

        assertEquals(original, decoded);
    }

    @Test
    void shouldRoundTripRejectedEvent() throws IOException {
        ItemRejected original = rejectedEvent();

        ItemRejected decoded = ItemRejected.parseFrom(original.toByteArray());

        assertEquals(original, decoded);
    }

    @Test
    void shouldTolerateUnknownAdditiveFieldsOnAnalyzedEvent() throws IOException {
        ItemAnalyzed original = analyzedEvent();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(original.toByteArray());

        CodedOutputStream codedOutput = CodedOutputStream.newInstance(bytes);
        codedOutput.writeString(1000, "future-field");
        codedOutput.flush();

        ItemAnalyzed decoded = ItemAnalyzed.parseFrom(bytes.toByteArray());

        assertEquals(original.getNormalizedItemId(), decoded.getNormalizedItemId());
        assertEquals(original.getEnvelope().getEventId(), decoded.getEnvelope().getEventId());
        assertTrue(decoded.getUnknownFields().hasField(1000));
    }

    private static ItemAnalyzed analyzedEvent() {
        Timestamp timestamp = Timestamp.newBuilder().setSeconds(1_725_817_600L).build();
        return ItemAnalyzed.newBuilder()
                .setEnvelope(envelope("analysis.item-analyzed.v1", "analysis-event-01", timestamp))
                .setSourceEventId("raw-event-01")
                .setRawItemId("raw-01")
                .setNormalizedItemId("normalized-01")
                .setSourceId("source-01")
                .setMonitoringProfileId("profile-01")
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
                .setNormalizedItemId("normalized-01")
                .setSourceId("source-01")
                .setMonitoringProfileId("profile-01")
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
                .setCorrelationId("run-01")
                .setTraceparent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                .setProducer("analysis")
                .setSchemaVersion("v1")
                .build();
    }
}
