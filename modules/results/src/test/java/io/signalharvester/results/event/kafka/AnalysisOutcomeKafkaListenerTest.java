package io.signalharvester.results.event.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Timestamp;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetStrategy;
import io.signalharvester.events.analysis.v1.ItemAnalyzed;
import io.signalharvester.events.analysis.v1.ItemRejected;
import io.signalharvester.events.common.v1.EventEnvelope;
import io.signalharvester.results.application.AnalysisOutcomeProjector;
import io.signalharvester.results.configuration.ResultsKafkaReliabilityConfiguration;
import io.signalharvester.results.model.AnalyzedResult;
import io.signalharvester.results.model.RejectedResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link AnalysisOutcomeKafkaListener} projection dispatch, bounded retry, dead-letter handling,
 * and framework-owned synchronous per-record commits for analyzed and rejected terminal events.
 *
 * <p>Related specifications: {@code backend-reliability-failure-handling},
 * {@code backend-kafka-offset-commit-failure-separation}, and
 * {@code backend-kafka-listener-interruption-fencing}.</p>
 *
 * <p>Features: {@code RESULTS.MATERIALIZATION}, {@code RELIABILITY.KAFKA_RETRY}, {@code RELIABILITY.DEAD_LETTER}.</p>
 */
class AnalysisOutcomeKafkaListenerTest {

    private static final String NORMALIZED_ITEM_ID = "a".repeat(64);
    private static final String RAW_ITEM_ID = "raw-01";
    private static final String SOURCE_EVENT_ID = "source-event-01";
    private static final String ANALYZED_TOPIC = "analyzed-items";
    private static final String REJECTED_TOPIC = "rejected-items";

    /** Persist an analyzed event before successful listener completion. */
    @Test
    void shouldProjectAnalyzedBeforeSuccessfulCompletion() {
        RecordingProjector projector = new RecordingProjector();
        RecordingDeadLetterPublisher deadLetters = new RecordingDeadLetterPublisher();
        AnalysisOutcomeKafkaListener listener = listener(projector, deadLetters, 3);

        listener.receiveAnalyzed(
                NORMALIZED_ITEM_ID,
                analyzedEvent().toByteArray(),
                7L,
                2,
                ANALYZED_TOPIC);

        assertEquals(NORMALIZED_ITEM_ID, projector.analyzed().get().normalizedItemId());
        assertTrue(deadLetters.failures.isEmpty());
    }

    /** Persist a rejected event before successful listener completion. */
    @Test
    void shouldProjectRejectedBeforeSuccessfulCompletion() {
        RecordingProjector projector = new RecordingProjector();
        RecordingDeadLetterPublisher deadLetters = new RecordingDeadLetterPublisher();
        AnalysisOutcomeKafkaListener listener = listener(projector, deadLetters, 3);

        listener.receiveRejected(
                NORMALIZED_ITEM_ID,
                rejectedEvent().toByteArray(),
                11L,
                1,
                REJECTED_TOPIC);

        assertEquals(SOURCE_EVENT_ID, projector.rejected().get().sourceEventId());
    }

    /** Retry transient projection failures and complete after the projection recovers. */
    @Test
    void shouldRetryProjectionFailureAndCompleteAfterRecovery() {
        AtomicInteger attempts = new AtomicInteger();
        AnalysisOutcomeProjector projector = new AnalysisOutcomeProjector() {
            @Override
            public void projectAnalyzed(AnalyzedResult result) {
                if (attempts.incrementAndGet() < 3) {
                    throw new IllegalStateException("database temporarily unavailable");
                }
            }

            @Override
            public void projectRejected(RejectedResult result) {
                throw new AssertionError("unexpected rejected projection");
            }
        };
        RecordingDeadLetterPublisher deadLetters = new RecordingDeadLetterPublisher();
        AnalysisOutcomeKafkaListener listener = listener(projector, deadLetters, 3);

        listener.receiveAnalyzed(
                NORMALIZED_ITEM_ID,
                analyzedEvent().toByteArray(),
                5L,
                0,
                ANALYZED_TOPIC);

        assertEquals(3, attempts.get());
        assertTrue(deadLetters.failures.isEmpty());
    }

    /** Dead-letter an exhausted projection failure before successful listener completion. */
    @Test
    void shouldDeadLetterExhaustedProjectionFailureBeforeCompletion() {
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
        RecordingDeadLetterPublisher deadLetters = new RecordingDeadLetterPublisher();
        AnalysisOutcomeKafkaListener listener = listener(projector, deadLetters, 2);

        listener.receiveAnalyzed(
                NORMALIZED_ITEM_ID,
                analyzedEvent().toByteArray(),
                5L,
                0,
                ANALYZED_TOPIC);

        Failure failure = deadLetters.failures.getFirst();
        assertEquals(2, failure.attempts());
        assertTrue(failure.retryable());
    }

    /** Dead-letter a mismatched Kafka key immediately without projecting the event. */
    @Test
    void shouldDeadLetterMismatchedKafkaKeyWithoutRetry() {
        RecordingProjector projector = new RecordingProjector();
        RecordingDeadLetterPublisher deadLetters = new RecordingDeadLetterPublisher();
        AnalysisOutcomeKafkaListener listener = listener(projector, deadLetters, 3);

        listener.receiveAnalyzed(
                "wrong-key",
                analyzedEvent().toByteArray(),
                3L,
                0,
                ANALYZED_TOPIC);

        assertNull(projector.analyzed().get());
        assertEquals(1, deadLetters.failures.getFirst().attempts());
        assertFalse(deadLetters.failures.getFirst().retryable());
    }

    /** Dead-letter malformed Protobuf immediately without projecting the event. */
    @Test
    void shouldDeadLetterMalformedPayloadWithoutRetry() {
        RecordingProjector projector = new RecordingProjector();
        RecordingDeadLetterPublisher deadLetters = new RecordingDeadLetterPublisher();
        AnalysisOutcomeKafkaListener listener = listener(projector, deadLetters, 3);

        listener.receiveRejected(
                NORMALIZED_ITEM_ID,
                new byte[] {(byte) 0x80},
                4L,
                0,
                REJECTED_TOPIC);

        assertNull(projector.rejected().get());
        assertEquals(1, deadLetters.failures.getFirst().attempts());
    }

    /** Delegate synchronous offset commit to Micronaut after successful listener completion. */
    @Test
    void shouldUseSynchronousPerRecordOffsetStrategy() {
        KafkaListener annotation = AnalysisOutcomeKafkaListener.class.getAnnotation(KafkaListener.class);

        assertEquals(OffsetStrategy.SYNC_PER_RECORD, annotation.offsetStrategy());
    }

    /** Propagate dead-letter publication failure so the framework does not commit the source offset. */
    @Test
    void shouldPropagateWhenDeadLetterPublicationFails() {
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
        ResultsDeadLetterPublisher deadLetters = (sourceTopic, sourcePartition, sourceOffset, sourceKey,
                sourcePayload, failure, attempts, retryable) -> {
            throw new IllegalStateException("DLQ unavailable");
        };
        AnalysisOutcomeKafkaListener listener = listener(projector, deadLetters, 1);

        assertThrows(IllegalStateException.class, () -> listener.receiveAnalyzed(
                NORMALIZED_ITEM_ID,
                analyzedEvent().toByteArray(),
                5L,
                0,
                ANALYZED_TOPIC));

    }

    /** Propagate lifecycle interruption instead of classifying interrupted projection as a terminal DLQ failure. */
    @Test
    void shouldPropagateInterruptedProjectionWithoutDeadLettering() {
        AnalysisOutcomeProjector projector = new AnalysisOutcomeProjector() {
            @Override
            public void projectAnalyzed(AnalyzedResult result) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("projection interrupted");
            }

            @Override
            public void projectRejected(RejectedResult result) {
                throw new AssertionError("unexpected rejected projection");
            }
        };
        RecordingDeadLetterPublisher deadLetters = new RecordingDeadLetterPublisher();
        AnalysisOutcomeKafkaListener listener = listener(projector, deadLetters, 1, Duration.ZERO);

        try {
            assertThrows(IllegalStateException.class, () -> listener.receiveAnalyzed(
                    NORMALIZED_ITEM_ID,
                    analyzedEvent().toByteArray(),
                    5L,
                    0,
                    ANALYZED_TOPIC));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(deadLetters.failures.isEmpty());
        } finally {
            Thread.interrupted();
        }
    }

    /** Reject retry backoff that exceeds the bounded listener policy. */
    @Test
    void shouldRejectRetryBackoffLongerThanFiveSeconds() {
        RecordingProjector projector = new RecordingProjector();
        RecordingDeadLetterPublisher deadLetters = new RecordingDeadLetterPublisher();

        assertThrows(IllegalArgumentException.class, () -> listener(projector, deadLetters, 3, Duration.ofSeconds(6)));
    }

    private static AnalysisOutcomeKafkaListener listener(
            AnalysisOutcomeProjector projector, ResultsDeadLetterPublisher deadLetters, int maxAttempts) {
        return listener(projector, deadLetters, maxAttempts, Duration.ZERO);
    }

    private static AnalysisOutcomeKafkaListener listener(
            AnalysisOutcomeProjector projector, ResultsDeadLetterPublisher deadLetters, int maxAttempts, Duration backoff) {
        ResultsKafkaReliabilityConfiguration configuration = new ResultsKafkaReliabilityConfiguration() {
            @Override
            public int getMaxAttempts() {
                return maxAttempts;
            }

            @Override
            public Duration getRetryBackoff() {
                return backoff;
            }

            @Override
            public String getDeadLetterTopic() {
                return "results-dlq";
            }

            @Override
            public Duration getReplayReadTimeout() {
                return Duration.ofSeconds(2);
            }

            @Override
            public int getReplayMaxConcurrency() {
                return 1;
            }
        };
        return new AnalysisOutcomeKafkaListener(
                new AnalysisOutcomeKafkaRecordDecoder(new AnalysisOutcomeMapper()),
                projector,
                configuration,
                deadLetters);
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

    private static final class RecordingDeadLetterPublisher implements ResultsDeadLetterPublisher {
        private final List<Failure> failures = new ArrayList<>();

        @Override
        public void publish(
                String sourceTopic,
                int sourcePartition,
                long sourceOffset,
                String sourceKey,
                byte[] sourcePayload,
                RuntimeException failure,
                int attempts,
                boolean retryable) {
            failures.add(new Failure(failure, attempts, retryable));
        }
    }

    private record Failure(RuntimeException failure, int attempts, boolean retryable) {
    }
}
