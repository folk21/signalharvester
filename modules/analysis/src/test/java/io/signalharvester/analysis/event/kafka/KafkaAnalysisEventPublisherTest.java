package io.signalharvester.analysis.event.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.signalharvester.analysis.application.AnalysisDecision;
import io.signalharvester.analysis.configuration.AnalysisKafkaConfiguration;
import io.signalharvester.analysis.event.AnalysisPublicationException;
import io.signalharvester.analysis.event.AnalysisPublicationResult;
import io.signalharvester.analysis.event.AnalyzedItem;
import io.signalharvester.analysis.event.RejectedItem;
import io.signalharvester.analysis.model.NormalizedContentItem;
import io.signalharvester.events.analysis.v1.ItemAnalyzed;
import io.signalharvester.events.analysis.v1.ItemRejected;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link KafkaAnalysisEventPublisher} and {@link AnalysisEventMapper} topic/key selection,
 * terminal-event payload mapping, provenance propagation, and publication failure normalization.
 *
 * <p>Related specifications: {@code backend-analysis-normalization-deduplication}, {@code backend-event-contracts}.</p>
 */
class KafkaAnalysisEventPublisherTest {

    private static final String ANALYZED_TOPIC = "analysis.analyzed.test";
    private static final String REJECTED_TOPIC = "analysis.rejected.test";
    private static final Instant EVENT_TIME = Instant.parse("2026-09-13T08:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String SOURCE_EVENT_ID = "source-event-01";
    private static final String RUN_ID = "run-01";
    private static final String RAW_ITEM_ID = "raw-01";
    private static final String NORMALIZED_ITEM_ID = "normalized-01";
    private static final String SOURCE_ID = "source-01";
    private static final String PROFILE_ID = "profile-01";
    private static final String DUPLICATE_EXPLANATION = "Already accepted for this profile";
    private static final String BROKER_FAILURE_MESSAGE = "broker unavailable";
    private static final String TRACEPARENT =
            "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";

    /**
     * Publish analyzed with normalized item key and complete provenance.
     */
    @Test
    void shouldPublishAnalyzedWithNormalizedItemKeyAndCompleteProvenance() throws Exception {
        AtomicReference<SentRecord> sent = new AtomicReference<>();
        KafkaAnalysisEventPublisher publisher = publisher((topic, key, payload) ->
                sent.set(new SentRecord(topic, key, payload)));
        NormalizedContentItem item = item();
        AnalysisDecision decision = new AnalysisDecision(
                true,
                "MATCHED_KEYWORDS",
                75,
                List.of("java", "kafka"),
                "Matched configured keywords",
                "keyword-v1");

        AnalysisPublicationResult result = publisher.publishAnalyzed(new AnalyzedItem(item, decision));

        assertEquals(ANALYZED_TOPIC, sent.get().topic());
        assertEquals(item.normalizedItemId(), sent.get().key());
        ItemAnalyzed event = ItemAnalyzed.parseFrom(sent.get().payload());
        assertEquals(EVENT_ID.toString(), event.getEnvelope().getEventId());
        assertEquals("analysis.item-analyzed.v1", event.getEnvelope().getEventType());
        assertEquals(EVENT_TIME.getEpochSecond(), event.getEnvelope().getOccurredAt().getSeconds());
        assertEquals(item.correlationId(), event.getEnvelope().getCorrelationId());
        assertEquals(item.traceparent().orElseThrow(), event.getEnvelope().getTraceparent());
        assertEquals("analysis", event.getEnvelope().getProducer());
        assertEquals("v1", event.getEnvelope().getSchemaVersion());
        assertEquals(item.sourceEventId(), event.getSourceEventId());
        assertEquals(item.rawItemId(), event.getRawItemId());
        assertEquals(item.normalizedItemId(), event.getNormalizedItemId());
        assertEquals(item.sourceId(), event.getSourceId());
        assertEquals(item.monitoringProfileId(), event.getMonitoringProfileId());
        assertEquals(item.informationCategory(), event.getInformationCategory());
        assertEquals(item.externalId().orElseThrow(), event.getExternalId());
        assertEquals(item.title().orElseThrow(), event.getTitle());
        assertEquals(item.url().toString(), event.getUrl());
        assertEquals(item.content(), event.getNormalizedContent());
        assertEquals(item.contentType(), event.getContentType());
        assertEquals(item.attributes(), event.getAttributesMap());
        assertEquals(decision.relevant(), event.getRelevant());
        assertEquals(decision.classification(), event.getClassification());
        assertEquals(decision.score(), event.getScore());
        assertEquals(decision.tags(), event.getTagsList());
        assertEquals(decision.explanation(), event.getExplanation());
        assertEquals(decision.analyzer(), event.getAnalyzer());
        assertEquals(item.publishedAt().orElseThrow().getEpochSecond(), event.getPublishedAt().getSeconds());
        assertEquals(EVENT_ID.toString(), result.eventId());
        assertEquals(ANALYZED_TOPIC, result.topic());
        assertEquals(item.normalizedItemId(), result.normalizedItemId());
    }

    /**
     * Publish rejected with normalized item key and duplicate reason.
     */
    @Test
    void shouldPublishRejectedWithNormalizedItemKeyAndDuplicateReason() throws Exception {
        AtomicReference<SentRecord> sent = new AtomicReference<>();
        KafkaAnalysisEventPublisher publisher = publisher((topic, key, payload) ->
                sent.set(new SentRecord(topic, key, payload)));
        NormalizedContentItem item = item();

        AnalysisPublicationResult result = publisher.publishRejected(
                new RejectedItem(item, "DUPLICATE", DUPLICATE_EXPLANATION));

        assertEquals(REJECTED_TOPIC, sent.get().topic());
        assertEquals(item.normalizedItemId(), sent.get().key());
        ItemRejected event = ItemRejected.parseFrom(sent.get().payload());
        assertEquals(EVENT_ID.toString(), event.getEnvelope().getEventId());
        assertEquals("analysis.item-rejected.v1", event.getEnvelope().getEventType());
        assertEquals(item.correlationId(), event.getEnvelope().getCorrelationId());
        assertEquals(item.traceparent().orElseThrow(), event.getEnvelope().getTraceparent());
        assertEquals(item.sourceEventId(), event.getSourceEventId());
        assertEquals(item.rawItemId(), event.getRawItemId());
        assertEquals(item.normalizedItemId(), event.getNormalizedItemId());
        assertEquals(item.sourceId(), event.getSourceId());
        assertEquals(item.monitoringProfileId(), event.getMonitoringProfileId());
        assertEquals(item.informationCategory(), event.getInformationCategory());
        assertEquals("DUPLICATE", event.getReasonCode());
        assertEquals(DUPLICATE_EXPLANATION, event.getExplanation());
        assertEquals(EVENT_ID.toString(), result.eventId());
        assertEquals(REJECTED_TOPIC, result.topic());
        assertEquals(item.normalizedItemId(), result.normalizedItemId());
    }

    /**
     * Normalize Kafka client failure to analysis publication exception.
     */
    @Test
    void shouldNormalizeKafkaClientFailureToAnalysisPublicationException() {
        KafkaAnalysisEventPublisher publisher = publisher((topic, key, payload) -> {
            throw new IllegalStateException(BROKER_FAILURE_MESSAGE);
        });
        NormalizedContentItem item = item();
        AnalysisDecision decision = new AnalysisDecision(
                true, "MATCHED_KEYWORDS", 100, List.of("java"), "Matched", "keyword-v1");

        AnalysisPublicationException failure = assertThrows(
                AnalysisPublicationException.class,
                () -> publisher.publishAnalyzed(new AnalyzedItem(item, decision)));

        assertEquals(item.normalizedItemId(), failure.normalizedItemId());
        assertEquals(ANALYZED_TOPIC, failure.topic());
        assertEquals(BROKER_FAILURE_MESSAGE, failure.getCause().getMessage());
    }

    private static KafkaAnalysisEventPublisher publisher(AnalysisKafkaClient client) {
        AnalysisKafkaConfiguration configuration = new AnalysisKafkaConfiguration() {
            @Override
            public String getItemAnalyzedTopic() {
                return ANALYZED_TOPIC;
            }

            @Override
            public String getItemRejectedTopic() {
                return REJECTED_TOPIC;
            }
        };
        AnalysisEventMapper mapper = new AnalysisEventMapper(
                Clock.fixed(EVENT_TIME, ZoneOffset.UTC),
                () -> EVENT_ID);
        return new KafkaAnalysisEventPublisher(client, configuration, mapper);
    }

    private static NormalizedContentItem item() {
        return new NormalizedContentItem(
                SOURCE_EVENT_ID,
                RUN_ID,
                Optional.of(TRACEPARENT),
                Instant.parse("2026-09-13T07:59:00Z"),
                RAW_ITEM_ID,
                NORMALIZED_ITEM_ID,
                SOURCE_ID,
                PROFILE_ID,
                "JOB",
                Optional.of("job-01"),
                Optional.of("Senior Java Engineer"),
                URI.create("https://example.test/jobs/1"),
                "Java Kafka PostgreSQL",
                "text/plain",
                Map.of("location", "Remote"),
                Optional.of(Instant.parse("2026-09-13T07:55:00Z")));
    }

    private record SentRecord(String topic, String key, byte[] payload) {
        private SentRecord {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}
