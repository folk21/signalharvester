package io.signalharvester.eventobservation.event.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Timestamp;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetStrategy;
import io.signalharvester.eventobservation.application.EventObservationRecorder;
import io.signalharvester.eventobservation.configuration.EventObservationKafkaReliabilityConfiguration;
import io.signalharvester.eventobservation.model.ObservedEventInput;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import io.signalharvester.events.common.v1.EventEnvelope;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link EventObservationKafkaListener} bounded retry and dead-letter handling without allowing
 * a failed diagnostic record to block its Kafka partition indefinitely.
 *
 * <p>Related specifications: {@code backend-reliability-failure-handling} and
 * {@code backend-kafka-offset-commit-failure-separation}.</p>
 *
 * <p>Features: {@code DIAGNOSTICS.EVENT_OBSERVATION}, {@code RELIABILITY.KAFKA_RETRY}, {@code RELIABILITY.DEAD_LETTER}.</p>
 */
class EventObservationKafkaListenerTest {

    private static final String TOPIC = "raw-items";
    private static final String RAW_ITEM_ID = "raw-01";

    /** Retry persistence failures and complete when recording eventually succeeds. */
    @Test
    void shouldRetryRecordingFailureAndCompleteAfterRecovery() {
        AtomicInteger attempts = new AtomicInteger();
        EventObservationRecorder recorder = event -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("database temporarily unavailable");
            }
        };
        RecordingDeadLetterPublisher deadLetters = new RecordingDeadLetterPublisher();
        EventObservationKafkaListener listener = listener(recorder, deadLetters, 3);

        listener.receiveRaw(RAW_ITEM_ID, rawEvent().toByteArray(), 7L, 2, TOPIC);

        assertEquals(3, attempts.get());
        assertTrue(deadLetters.failures.isEmpty());
    }

    /** Dead-letter malformed records immediately and complete only after DLQ publication succeeds. */
    @Test
    void shouldDeadLetterMalformedPayloadAndComplete() {
        AtomicReference<ObservedEventInput> recorded = new AtomicReference<>();
        RecordingDeadLetterPublisher deadLetters = new RecordingDeadLetterPublisher();
        EventObservationKafkaListener listener = listener(recorded::set, deadLetters, 3);

        listener.receiveRaw(RAW_ITEM_ID, new byte[] {0x0A, 0x05, 0x01}, 7L, 2, TOPIC);

        assertNull(recorded.get());
        Failure failure = deadLetters.failures.getFirst();
        assertEquals(1, failure.attempts());
        assertFalse(failure.retryable());
    }

    /** Delegate synchronous offset commit to Micronaut after successful listener completion. */
    @Test
    void shouldUseSynchronousPerRecordOffsetStrategy() {
        KafkaListener annotation = EventObservationKafkaListener.class.getAnnotation(KafkaListener.class);

        assertEquals(OffsetStrategy.SYNC_PER_RECORD, annotation.offsetStrategy());
    }

    /** Propagate terminal DLQ publication failure so the framework does not commit the source offset. */
    @Test
    void shouldPropagateWhenDeadLetterPublicationFails() {
        EventObservationRecorder recorder = event -> {
            throw new IllegalStateException("database unavailable");
        };
        EventObservationDeadLetterPublisher deadLetters = (sourceTopic, sourcePartition, sourceOffset, sourceKey,
                sourcePayload, failure, attempts, retryable) -> {
            throw new IllegalStateException("DLQ unavailable");
        };
        EventObservationKafkaListener listener = listener(recorder, deadLetters, 1);

        assertThrows(IllegalStateException.class, () ->
                listener.receiveRaw(RAW_ITEM_ID, rawEvent().toByteArray(), 7L, 2, TOPIC));

    }

    /** Reject retry backoff that exceeds the bounded listener policy. */
    @Test
    void shouldRejectRetryBackoffLongerThanFiveSeconds() {
        RecordingDeadLetterPublisher deadLetters = new RecordingDeadLetterPublisher();
        EventObservationRecorder recorder = event -> {};

        assertThrows(IllegalArgumentException.class, () -> listener(recorder, deadLetters, 3, Duration.ofSeconds(6)));
    }

    private static EventObservationKafkaListener listener(
            EventObservationRecorder recorder, EventObservationDeadLetterPublisher deadLetters, int maxAttempts) {
        return listener(recorder, deadLetters, maxAttempts, Duration.ZERO);
    }

    private static EventObservationKafkaListener listener(
            EventObservationRecorder recorder, EventObservationDeadLetterPublisher deadLetters, int maxAttempts, Duration backoff) {
        EventObservationKafkaReliabilityConfiguration configuration = new EventObservationKafkaReliabilityConfiguration() {
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
                return "event-observation-dlq";
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
        return new EventObservationKafkaListener(
                new EventObservationKafkaRecordDecoder(new EventObservationMapper()),
                recorder,
                configuration,
                deadLetters);
    }

    private static RawItemDiscovered rawEvent() {
        return RawItemDiscovered.newBuilder()
                .setEnvelope(EventEnvelope.newBuilder()
                        .setEventId("raw-event-01")
                        .setEventType("collection.raw-item-discovered.v1")
                        .setOccurredAt(Timestamp.newBuilder().setSeconds(1_700_000_000L).build())
                        .setCorrelationId("run-01")
                        .setProducer("collection")
                        .setSchemaVersion("v1")
                        .build())
                .setRawItemId(RAW_ITEM_ID)
                .setSourceId("source-01")
                .setMonitoringProfileId("profile-01")
                .setInformationCategory("JOB")
                .setUrl("https://example.test/jobs/1")
                .setContent("Java Kafka")
                .setContentType("text/plain")
                .build();
    }

    private static final class RecordingDeadLetterPublisher implements EventObservationDeadLetterPublisher {
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
