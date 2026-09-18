package io.signalharvester.eventobservation.event.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Timestamp;
import io.signalharvester.events.analysis.v1.ItemAnalyzed;
import io.signalharvester.events.analysis.v1.ItemRejected;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import io.signalharvester.events.common.v1.EventEnvelope;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Verifies stable diagnostic decoding of the currently published Protobuf event families.
 *
 * <p>Feature: {@code DIAGNOSTICS.EVENT_OBSERVATION}.</p>
 */
class EventObservationMapperTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-09-14T12:00:00Z");
    private final EventObservationMapper mapper = new EventObservationMapper(() -> OBSERVED_AT);

    /** Preserve raw-item provenance without retaining the potentially large raw content body. */
    @Test
    void shouldDecodeRawItemMetadata() {
        RawItemDiscovered event = RawItemDiscovered.newBuilder()
                .setEnvelope(envelope("raw-event", "collection.raw-item-discovered.v1", "collection"))
                .setRawItemId("raw-1")
                .setSourceId("source-1")
                .setMonitoringProfileId("profile-1")
                .setInformationCategory("JOB")
                .setExternalId("external-1")
                .setTitle("Senior Java Engineer")
                .setUrl("https://example.test/jobs/1")
                .setContent("large body that must not be copied into observation history")
                .setContentType("text/plain")
                .build();

        var mapped = mapper.mapRaw(event, "raw-1", "raw-topic", 1, 9L);

        assertEquals("RawItemDiscovered", mapped.payloadType());
        assertEquals("raw-1", mapped.rawItemId().orElseThrow());
        assertEquals("Senior Java Engineer", mapped.title().orElseThrow());
        assertEquals(OBSERVED_AT, mapped.observedAt());
        assertTrue(mapped.explanation().isEmpty());
    }

    /** Decode analyzer diagnostics and logical item identity from analyzed outcomes. */
    @Test
    void shouldDecodeAnalyzedPayloadFields() {
        ItemAnalyzed event = ItemAnalyzed.newBuilder()
                .setEnvelope(envelope("analyzed-event", "analysis.item-analyzed.v1", "analysis"))
                .setSourceEventId("raw-event")
                .setRawItemId("raw-1")
                .setNormalizedItemId("a".repeat(64))
                .setSourceId("source-1")
                .setMonitoringProfileId("profile-1")
                .setInformationCategory("JOB")
                .setUrl("https://example.test/jobs/1")
                .setNormalizedContent("Java Kafka")
                .setContentType("text/plain")
                .setRelevant(true)
                .setClassification("MATCHED")
                .setScore(90)
                .setExplanation("Matched Java")
                .setAnalyzer("keyword-v1")
                .build();

        var mapped = mapper.mapAnalyzed(event, "a".repeat(64), "analysis-topic", 0, 15L);

        assertEquals(90, mapped.score().orElseThrow());
        assertEquals("keyword-v1", mapped.analyzer().orElseThrow());
        assertEquals("raw-event", mapped.sourceEventId().orElseThrow());
        assertEquals("a".repeat(64), mapped.normalizedItemId().orElseThrow());
    }

    /** Decode rejection reason and explanation for technical duplicate/failure inspection. */
    @Test
    void shouldDecodeRejectedPayloadFields() {
        ItemRejected event = ItemRejected.newBuilder()
                .setEnvelope(envelope("rejected-event", "analysis.item-rejected.v1", "analysis"))
                .setSourceEventId("raw-event")
                .setRawItemId("raw-1")
                .setNormalizedItemId("a".repeat(64))
                .setSourceId("source-1")
                .setMonitoringProfileId("profile-1")
                .setInformationCategory("JOB")
                .setReasonCode("DUPLICATE")
                .setExplanation("Already accepted")
                .build();

        var mapped = mapper.mapRejected(event, "a".repeat(64), "reject-topic", 0, 16L);

        assertEquals("DUPLICATE", mapped.reasonCode().orElseThrow());
        assertEquals("Already accepted", mapped.explanation().orElseThrow());
    }

    private static EventEnvelope envelope(String eventId, String type, String producer) {
        return EventEnvelope.newBuilder()
                .setEventId(eventId)
                .setEventType(type)
                .setOccurredAt(Timestamp.newBuilder().setSeconds(1_700_000_000L).build())
                .setCorrelationId("run-1")
                .setTraceparent("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01")
                .setProducer(producer)
                .setSchemaVersion("v1")
                .build();
    }
}
