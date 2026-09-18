package io.signalharvester.analysis.event.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Timestamp;
import io.signalharvester.analysis.application.RawItemProcessingResult;
import io.signalharvester.analysis.application.RawItemProcessingStatus;
import io.signalharvester.analysis.application.RawItemProcessor;
import io.signalharvester.analysis.configuration.AnalysisKafkaReliabilityConfiguration;
import io.signalharvester.analysis.configuration.KeywordAnalysisConfiguration;
import io.signalharvester.events.collection.v1.KeywordAnalysisSettings;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import io.signalharvester.events.common.v1.EventEnvelope;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link RawItemKafkaListener} bounded retry, poison-record dead-letter handling, and manual
 * offset commits without acknowledging records before successful processing or DLQ publication.
 *
 * <p>Related specification: {@code backend-reliability-failure-handling}.</p>
 */
class RawItemKafkaListenerTest {

    private static final String TOPIC = "raw-items";
    private static final String RAW_ITEM_ID = "raw-01";
    private static final String SOURCE_EVENT_ID = "source-event-01";
    private static final String RUN_ID = "run-01";
    private static final String SOURCE_ID = "source-01";
    private static final String PROFILE_ID = "profile-01";

    /** Commit after the first successful processing attempt. */
    @Test
    void shouldCommitAfterSuccessfulProcessing() {
        AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed = new AtomicReference<>();
        RecordingDeadLetterPublisher deadLetters = new RecordingDeadLetterPublisher();
        RawItemProcessor processor = rawItem -> successfulResult();
        RawItemKafkaListener listener = listener(processor, deadLetters, 3);

        listener.receive(RAW_ITEM_ID, event().toByteArray(), 7L, 2, TOPIC, consumer(committed));

        assertCommitted(committed, 8L);
        assertTrue(deadLetters.failures.isEmpty());
    }

    /** Retry runtime processing failures within the configured bound and commit after recovery. */
    @Test
    void shouldRetryRuntimeFailureAndCommitAfterRecovery() {
        AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed = new AtomicReference<>();
        RecordingDeadLetterPublisher deadLetters = new RecordingDeadLetterPublisher();
        AtomicInteger attempts = new AtomicInteger();
        RawItemKafkaListener listener = listener(rawItem -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("database temporarily unavailable");
            }
            return successfulResult();
        }, deadLetters, 3);

        listener.receive(RAW_ITEM_ID, event().toByteArray(), 7L, 2, TOPIC, consumer(committed));

        assertEquals(3, attempts.get());
        assertCommitted(committed, 8L);
        assertTrue(deadLetters.failures.isEmpty());
    }

    /** Dead-letter an exhausted retryable failure before committing past the poison record. */
    @Test
    void shouldDeadLetterExhaustedRetryableFailureBeforeCommit() {
        AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed = new AtomicReference<>();
        RecordingDeadLetterPublisher deadLetters = new RecordingDeadLetterPublisher();
        AtomicInteger attempts = new AtomicInteger();
        RawItemKafkaListener listener = listener(rawItem -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("database unavailable");
        }, deadLetters, 3);

        listener.receive(RAW_ITEM_ID, event().toByteArray(), 7L, 2, TOPIC, consumer(committed));

        assertEquals(3, attempts.get());
        assertCommitted(committed, 8L);
        Failure failure = deadLetters.failures.getFirst();
        assertEquals(3, failure.attempts());
        assertTrue(failure.retryable());
        assertEquals("database unavailable", failure.failure().getMessage());
    }

    /** Dead-letter malformed protobuf immediately without invoking the processor. */
    @Test
    void shouldDeadLetterMalformedProtobufWithoutRetry() {
        AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed = new AtomicReference<>();
        AtomicBoolean processed = new AtomicBoolean();
        RecordingDeadLetterPublisher deadLetters = new RecordingDeadLetterPublisher();
        RawItemKafkaListener listener = listener(rawItem -> {
            processed.set(true);
            throw new AssertionError("processor must not run for malformed transport data");
        }, deadLetters, 3);

        listener.receive(RAW_ITEM_ID, new byte[] {0x0A, 0x05, 0x01}, 7L, 2, TOPIC, consumer(committed));

        assertFalse(processed.get());
        assertCommitted(committed, 8L);
        Failure failure = deadLetters.failures.getFirst();
        assertEquals(1, failure.attempts());
        assertFalse(failure.retryable());
        assertTrue(failure.failure().getMessage().contains("Invalid RawItemDiscovered"));
    }

    /** Dead-letter invalid captured Analysis settings immediately without invoking the processor. */
    @Test
    void shouldDeadLetterInvalidCapturedAnalysisSettingsWithoutRetry() {
        AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed = new AtomicReference<>();
        AtomicBoolean processed = new AtomicBoolean();
        RecordingDeadLetterPublisher deadLetters = new RecordingDeadLetterPublisher();
        RawItemKafkaListener listener = listener(rawItem -> {
            processed.set(true);
            throw new AssertionError("processor must not run for invalid captured Analysis settings");
        }, deadLetters, 3);
        RawItemDiscovered invalidEvent = event().toBuilder()
                .setAnalysisSettings(KeywordAnalysisSettings.newBuilder()
                        .addKeywords("java")
                        .setMinimumMatches(2))
                .build();

        listener.receive(RAW_ITEM_ID, invalidEvent.toByteArray(), 7L, 2, TOPIC, consumer(committed));

        assertFalse(processed.get());
        assertCommitted(committed, 8L);
        Failure failure = deadLetters.failures.getFirst();
        assertEquals(1, failure.attempts());
        assertFalse(failure.retryable());
        assertTrue(failure.failure().getMessage().contains("minimumMatches"));
    }

    /** Dead-letter a key mismatch immediately without invoking the processor. */
    @Test
    void shouldDeadLetterKeyMismatchWithoutRetry() {
        AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed = new AtomicReference<>();
        AtomicBoolean processed = new AtomicBoolean();
        RecordingDeadLetterPublisher deadLetters = new RecordingDeadLetterPublisher();
        RawItemKafkaListener listener = listener(rawItem -> {
            processed.set(true);
            throw new AssertionError("processor must not run for key mismatch");
        }, deadLetters, 3);

        listener.receive("different-raw-id", event().toByteArray(), 7L, 2, TOPIC, consumer(committed));

        assertFalse(processed.get());
        assertCommitted(committed, 8L);
        assertFalse(deadLetters.failures.getFirst().retryable());
    }

    /** Leave the source offset uncommitted when dead-letter publication itself fails. */
    @Test
    void shouldLeaveOffsetUncommittedWhenDeadLetterPublicationFails() {
        AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed = new AtomicReference<>();
        AnalysisDeadLetterPublisher deadLetters = (sourceTopic, sourcePartition, sourceOffset, sourceKey,
                sourcePayload, failure, attempts, retryable) -> {
            throw new IllegalStateException("DLQ unavailable");
        };
        RawItemKafkaListener listener = listener(rawItem -> {
            throw new IllegalStateException("database unavailable");
        }, deadLetters, 1);

        assertThrows(IllegalStateException.class, () ->
                listener.receive(RAW_ITEM_ID, event().toByteArray(), 7L, 2, TOPIC, consumer(committed)));

        assertNull(committed.get());
    }


    /** Reject retry backoff that exceeds the bounded listener policy. */
    @Test
    void shouldRejectRetryBackoffLongerThanFiveSeconds() {
        RecordingDeadLetterPublisher deadLetters = new RecordingDeadLetterPublisher();
        RawItemProcessor processor = rawItem -> successfulResult();

        assertThrows(IllegalArgumentException.class, () -> listener(processor, deadLetters, 3, Duration.ofSeconds(6)));
    }

    private static RawItemKafkaListener listener(
            RawItemProcessor processor, AnalysisDeadLetterPublisher deadLetters, int maxAttempts) {
        return listener(processor, deadLetters, maxAttempts, Duration.ZERO);
    }

    private static RawItemKafkaListener listener(
            RawItemProcessor processor, AnalysisDeadLetterPublisher deadLetters, int maxAttempts, Duration backoff) {
        AnalysisKafkaReliabilityConfiguration configuration = new AnalysisKafkaReliabilityConfiguration() {
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
                return "analysis-dlq";
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
        KeywordAnalysisConfiguration legacyDefaults = new KeywordAnalysisConfiguration();
        legacyDefaults.setKeywords(List.of("legacy"));
        legacyDefaults.setMinimumMatches(1);
        return new RawItemKafkaListener(
                new RawItemKafkaRecordDecoder(new RawItemDiscoveredMapper(legacyDefaults)),
                processor,
                configuration,
                deadLetters);
    }

    private static RawItemProcessingResult successfulResult() {
        return new RawItemProcessingResult(
                RawItemProcessingStatus.ANALYZED,
                "normalized-01",
                "analysis-event-01",
                "analyzed-items");
    }

    private static RawItemDiscovered event() {
        return RawItemDiscovered.newBuilder()
                .setEnvelope(EventEnvelope.newBuilder()
                        .setEventId(SOURCE_EVENT_ID)
                        .setEventType("collection.raw-item-discovered.v1")
                        .setOccurredAt(Timestamp.newBuilder().setSeconds(1_789_000_000L).build())
                        .setCorrelationId(RUN_ID)
                        .setProducer("collection")
                        .setSchemaVersion("v1")
                        .build())
                .setRawItemId(RAW_ITEM_ID)
                .setSourceId(SOURCE_ID)
                .setMonitoringProfileId(PROFILE_ID)
                .setInformationCategory("JOB")
                .setUrl("https://example.test/jobs/1")
                .setContent("Java Kafka")
                .setContentType("text/plain")
                .build();
    }

    private static void assertCommitted(
            AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed, long expectedOffset) {
        Map<TopicPartition, OffsetAndMetadata> offsets = committed.get();
        assertEquals(1, offsets.size());
        assertEquals(expectedOffset, offsets.get(new TopicPartition(TOPIC, 2)).offset());
    }

    @SuppressWarnings("unchecked")
    private static Consumer<?, ?> consumer(AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed) {
        return (Consumer<?, ?>) Proxy.newProxyInstance(
                RawItemKafkaListenerTest.class.getClassLoader(),
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

    private static final class RecordingDeadLetterPublisher implements AnalysisDeadLetterPublisher {
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
