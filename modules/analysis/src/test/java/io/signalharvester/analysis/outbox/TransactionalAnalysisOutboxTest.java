package io.signalharvester.analysis.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.signalharvester.analysis.application.AnalysisDecision;
import io.signalharvester.analysis.configuration.AnalysisKafkaConfiguration;
import io.signalharvester.analysis.event.AnalyzedItem;
import io.signalharvester.analysis.event.RejectedItem;
import io.signalharvester.analysis.event.kafka.AnalysisEventMapper;
import io.signalharvester.analysis.model.NormalizedContentItem;
import io.signalharvester.analysis.observability.AnalysisObservability;
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
 * Verifies {@link TransactionalAnalysisOutbox} serializes stable terminal events before persistence.
 *
 * <p>Features: {@code ANALYSIS.OUTBOX}, {@code EVENTING.CORRELATION}.</p>
 */
class TransactionalAnalysisOutboxTest {

    private static final Instant EVENT_TIME = Instant.parse("2026-09-15T12:00:00Z");
    private static final String ANALYZED_TOPIC = "analyzed-items";
    private static final String REJECTED_TOPIC = "rejected-items";

    /** Stage analyzed event with its final Kafka topic, key, bytes, and event identity. */
    @Test
    void shouldStageAnalyzedEvent() throws Exception {
        RecordingStore store = new RecordingStore();
        TransactionalAnalysisOutbox outbox = outbox(store);
        NormalizedContentItem item = item();
        AnalysisDecision decision = new AnalysisDecision(
                true, "MATCHED_KEYWORDS", 80, List.of("java"), "Matched", "keyword-v1");

        var result = outbox.enqueueAnalyzed(new AnalyzedItem(item, decision));

        AnalysisOutboxEntry entry = store.entry.get();
        assertEquals(result.eventId(), entry.eventId());
        assertEquals(ANALYZED_TOPIC, entry.topic());
        assertEquals(item.normalizedItemId(), entry.eventKey());
        assertEquals(EVENT_TIME, entry.createdAt());
        assertEquals(item.traceparent(), entry.traceparent());
        ItemAnalyzed event = ItemAnalyzed.parseFrom(entry.payload());
        assertEquals(result.eventId(), event.getEnvelope().getEventId());
        assertEquals(item.sourceEventId(), event.getSourceEventId());
    }

    /** Stage rejected event with the same immutable bytes that the dispatcher will publish. */
    @Test
    void shouldStageRejectedEvent() throws Exception {
        RecordingStore store = new RecordingStore();
        TransactionalAnalysisOutbox outbox = outbox(store);
        NormalizedContentItem item = item();

        var result = outbox.enqueueRejected(new RejectedItem(item, "DUPLICATE", "Already seen"));

        AnalysisOutboxEntry entry = store.entry.get();
        assertEquals(REJECTED_TOPIC, entry.topic());
        assertEquals(item.normalizedItemId(), entry.eventKey());
        ItemRejected event = ItemRejected.parseFrom(entry.payload());
        assertEquals(result.eventId(), event.getEnvelope().getEventId());
        assertEquals("DUPLICATE", event.getReasonCode());
    }

    private static TransactionalAnalysisOutbox outbox(RecordingStore store) {
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
        AnalysisEventMapper mapper = new AnalysisEventMapper(Clock.fixed(EVENT_TIME, ZoneOffset.UTC));
        return new TransactionalAnalysisOutbox(
                store, configuration, mapper, new AnalysisObservability(Optional.empty(), Optional.empty()));
    }

    private static NormalizedContentItem item() {
        return new NormalizedContentItem(
                "source-event-1",
                "run-1",
                Optional.of("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"),
                EVENT_TIME.minusSeconds(60),
                "raw-1",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "source-1",
                "profile-1",
                "JOB",
                Optional.of("external-1"),
                Optional.of("Senior Java Engineer"),
                URI.create("https://example.test/jobs/1"),
                "Java Kafka PostgreSQL",
                "text/plain",
                Map.of("location", "Remote"),
                Optional.of(EVENT_TIME.minusSeconds(120)));
    }

    private static final class RecordingStore implements AnalysisOutboxStore {
        private final AtomicReference<AnalysisOutboxEntry> entry = new AtomicReference<>();

        @Override
        public void append(AnalysisOutboxEntry value) {
            entry.set(value);
        }

        @Override
        public AnalysisOutboxBacklog inspectBacklog() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AnalysisOutboxEntry> claimBatch(Instant now, UUID leaseToken, Instant leaseExpiresAt, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void renewLease(String eventId, UUID leaseToken, Instant renewedAt, Instant leaseExpiresAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markPublished(String eventId, UUID leaseToken, Instant publishedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markFailed(
                String eventId, UUID leaseToken, Instant failedAt, Instant nextAttemptAt, String failureMessage) {
            throw new UnsupportedOperationException();
        }
    }
}
