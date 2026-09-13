package io.signalharvester.results.event.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.Timestamp;
import io.signalharvester.events.analysis.v1.ItemAnalyzed;
import io.signalharvester.events.analysis.v1.ItemRejected;
import io.signalharvester.events.common.v1.EventEnvelope;
import io.signalharvester.results.application.AnalysisOutcomeProjector;
import io.signalharvester.results.model.AnalyzedResult;
import io.signalharvester.results.model.RejectedResult;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link AnalysisOutcomeKafkaListener} transport validation, Results projection dispatch,
 * and manual Kafka offset commit semantics for analyzed and rejected terminal events.
 *
 * <p>Related specification: {@code backend-results-persistence}.</p>
 */
class AnalysisOutcomeKafkaListenerTest {

    private static final String NORMALIZED_ITEM_ID = "a".repeat(64);
    private static final String RAW_ITEM_ID = "raw-01";
    private static final String SOURCE_EVENT_ID = "source-event-01";
    private static final String ANALYZED_TOPIC = "analyzed-items";
    private static final String REJECTED_TOPIC = "rejected-items";

    /**
     * Persist an analyzed event before committing the consumed offset.
     */
    @Test
    void shouldProjectAnalyzedBeforeCommittingOffset() {
        RecordingProjector projector = new RecordingProjector();
        AnalysisOutcomeKafkaListener listener = listener(projector);
        AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed = new AtomicReference<>();

        listener.receiveAnalyzed(
                NORMALIZED_ITEM_ID,
                analyzedEvent().toByteArray(),
                7L,
                2,
                ANALYZED_TOPIC,
                consumer(committed));

        assertEquals(NORMALIZED_ITEM_ID, projector.analyzed().get().normalizedItemId());
        assertEquals(new OffsetAndMetadata(8L), committed.get().get(new TopicPartition(ANALYZED_TOPIC, 2)));
    }

    /**
     * Persist a rejected event before committing the consumed offset.
     */
    @Test
    void shouldProjectRejectedBeforeCommittingOffset() {
        RecordingProjector projector = new RecordingProjector();
        AnalysisOutcomeKafkaListener listener = listener(projector);
        AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed = new AtomicReference<>();

        listener.receiveRejected(
                NORMALIZED_ITEM_ID,
                rejectedEvent().toByteArray(),
                11L,
                1,
                REJECTED_TOPIC,
                consumer(committed));

        assertEquals(SOURCE_EVENT_ID, projector.rejected().get().sourceEventId());
        assertEquals(new OffsetAndMetadata(12L), committed.get().get(new TopicPartition(REJECTED_TOPIC, 1)));
    }

    /**
     * Reject a mismatched Kafka key without projecting or committing the event.
     */
    @Test
    void shouldRejectMismatchedKafkaKeyWithoutCommit() {
        RecordingProjector projector = new RecordingProjector();
        AnalysisOutcomeKafkaListener listener = listener(projector);
        AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed = new AtomicReference<>();

        assertThrows(IllegalArgumentException.class, () -> listener.receiveAnalyzed(
                "wrong-key",
                analyzedEvent().toByteArray(),
                3L,
                0,
                ANALYZED_TOPIC,
                consumer(committed)));

        assertNull(projector.analyzed().get());
        assertNull(committed.get());
    }

    /**
     * Reject malformed Protobuf bytes without projecting or committing the event.
     */
    @Test
    void shouldRejectMalformedPayloadWithoutCommit() {
        RecordingProjector projector = new RecordingProjector();
        AnalysisOutcomeKafkaListener listener = listener(projector);
        AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed = new AtomicReference<>();

        assertThrows(IllegalArgumentException.class, () -> listener.receiveRejected(
                NORMALIZED_ITEM_ID,
                new byte[] {(byte) 0x80},
                4L,
                0,
                REJECTED_TOPIC,
                consumer(committed)));

        assertNull(projector.rejected().get());
        assertNull(committed.get());
    }


    /**
     * Reject semantically invalid analyzed data without projecting or committing the event.
     */
    @Test
    void shouldRejectInvalidMappedDomainDataWithoutCommit() {
        RecordingProjector projector = new RecordingProjector();
        AnalysisOutcomeKafkaListener listener = listener(projector);
        AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed = new AtomicReference<>();
        ItemAnalyzed invalid = analyzedEvent().toBuilder().clearSourceId().build();

        assertThrows(IllegalArgumentException.class, () -> listener.receiveAnalyzed(
                NORMALIZED_ITEM_ID,
                invalid.toByteArray(),
                6L,
                0,
                ANALYZED_TOPIC,
                consumer(committed)));

        assertNull(projector.analyzed().get());
        assertNull(committed.get());
    }

    /**
     * Leave the offset uncommitted when durable projection fails.
     */
    @Test
    void shouldLeaveOffsetUncommittedWhenProjectionFails() {
        AnalysisOutcomeProjector projector = new AnalysisOutcomeProjector() {
            @Override
            public void projectAnalyzed(AnalyzedResult result) {
                throw new IllegalStateException("database unavailable");
            }

            @Override
            public void projectRejected(RejectedResult result) {
                throw new AssertionError("unexpected rejected projection");
            }
        };
        AnalysisOutcomeKafkaListener listener = listener(projector);
        AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed = new AtomicReference<>();

        assertThrows(IllegalStateException.class, () -> listener.receiveAnalyzed(
                NORMALIZED_ITEM_ID,
                analyzedEvent().toByteArray(),
                5L,
                0,
                ANALYZED_TOPIC,
                consumer(committed)));

        assertNull(committed.get());
    }

    private static AnalysisOutcomeKafkaListener listener(AnalysisOutcomeProjector projector) {
        return new AnalysisOutcomeKafkaListener(new AnalysisOutcomeMapper(), projector);
    }

    private static ItemAnalyzed analyzedEvent() {
        return ItemAnalyzed.newBuilder()
                .setEnvelope(envelope("analysis-event-01", "analysis.item-analyzed.v1"))
                .setSourceEventId(SOURCE_EVENT_ID)
                .setRawItemId(RAW_ITEM_ID)
                .setNormalizedItemId(NORMALIZED_ITEM_ID)
                .setSourceId("source-01")
                .setMonitoringProfileId("profile-01")
                .setInformationCategory("JOB")
                .setTitle("Senior Java Engineer")
                .setUrl("https://example.test/jobs/1")
                .setNormalizedContent("Java Kafka")
                .setContentType("text/plain")
                .putAttributes("location", "Remote")
                .setRelevant(true)
                .setClassification("MATCHED_KEYWORDS")
                .setScore(100)
                .addTags("java")
                .setExplanation("Matched java")
                .setAnalyzer("keyword-v1")
                .build();
    }

    private static ItemRejected rejectedEvent() {
        return ItemRejected.newBuilder()
                .setEnvelope(envelope("rejection-event-01", "analysis.item-rejected.v1"))
                .setSourceEventId(SOURCE_EVENT_ID)
                .setRawItemId(RAW_ITEM_ID)
                .setNormalizedItemId(NORMALIZED_ITEM_ID)
                .setSourceId("source-01")
                .setMonitoringProfileId("profile-01")
                .setInformationCategory("JOB")
                .setReasonCode("DUPLICATE")
                .setExplanation("Already accepted")
                .build();
    }

    private static EventEnvelope envelope(String eventId, String eventType) {
        return EventEnvelope.newBuilder()
                .setEventId(eventId)
                .setEventType(eventType)
                .setOccurredAt(Timestamp.newBuilder().setSeconds(1_700_000_000L).build())
                .setCorrelationId("run-01")
                .setProducer("analysis")
                .setSchemaVersion("v1")
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Consumer<?, ?> consumer(AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed) {
        return (Consumer<?, ?>) Proxy.newProxyInstance(
                AnalysisOutcomeKafkaListenerTest.class.getClassLoader(),
                new Class<?>[] {Consumer.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("commitSync") && args != null && args.length == 1) {
                        committed.set((Map<TopicPartition, OffsetAndMetadata>) args[0]);
                        return null;
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "RecordingKafkaConsumer";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class RecordingProjector implements AnalysisOutcomeProjector {
        private final AtomicReference<AnalyzedResult> analyzed = new AtomicReference<>();
        private final AtomicReference<RejectedResult> rejected = new AtomicReference<>();

        @Override
        public void projectAnalyzed(AnalyzedResult result) {
            analyzed.set(result);
        }

        @Override
        public void projectRejected(RejectedResult result) {
            rejected.set(result);
        }

        AtomicReference<AnalyzedResult> analyzed() {
            return analyzed;
        }

        AtomicReference<RejectedResult> rejected() {
            return rejected;
        }
    }
}
