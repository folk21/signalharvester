package io.signalharvester.analysis.event.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.Timestamp;
import io.signalharvester.analysis.application.RawItemProcessingResult;
import io.signalharvester.analysis.application.RawItemProcessingStatus;
import io.signalharvester.analysis.application.RawItemProcessor;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import io.signalharvester.events.common.v1.EventEnvelope;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link RawItemKafkaListener} input validation and manual offset-commit semantics for valid,
 * poison, and processing-failure paths before analysis state is acknowledged.
 *
 * <p>Related specification: {@code backend-analysis-normalization-deduplication}.</p>
 */
class RawItemKafkaListenerTest {

    private static final String TOPIC = "raw-items";
    private static final String RAW_ITEM_ID = "raw-01";
    private static final String SOURCE_EVENT_ID = "source-event-01";
    private static final String RUN_ID = "run-01";
    private static final String SOURCE_ID = "source-01";
    private static final String PROFILE_ID = "profile-01";

    /**
     * Commit only after successful processing.
     */
    @Test
    void shouldCommitOnlyAfterSuccessfulProcessing() {
        AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed = new AtomicReference<>();
        RawItemProcessor processor = rawItem -> new RawItemProcessingResult(
                RawItemProcessingStatus.ANALYZED,
                "normalized-01",
                "analysis-event-01",
                "analyzed-items");
        RawItemKafkaListener listener = new RawItemKafkaListener(new RawItemDiscoveredMapper(), processor);

        listener.receive(RAW_ITEM_ID, event().toByteArray(), 7L, 2, TOPIC, consumer(committed));

        Map<TopicPartition, OffsetAndMetadata> offsets = committed.get();
        assertEquals(1, offsets.size());
        assertEquals(8L, offsets.get(new TopicPartition(TOPIC, 2)).offset());
    }

    /**
     * Leave offset uncommitted when processing fails.
     */
    @Test
    void shouldLeaveOffsetUncommittedWhenProcessingFails() {
        AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed = new AtomicReference<>();
        RawItemProcessor processor = rawItem -> {
            throw new IllegalStateException("terminal publication failed");
        };
        RawItemKafkaListener listener = new RawItemKafkaListener(new RawItemDiscoveredMapper(), processor);

        assertThrows(IllegalStateException.class, () ->
                listener.receive(RAW_ITEM_ID, event().toByteArray(), 7L, 2, TOPIC, consumer(committed)));

        assertNull(committed.get());
    }

    /**
     * Leave offset uncommitted and skip processing for malformed protobuf.
     */
    @Test
    void shouldLeaveOffsetUncommittedAndSkipProcessingForMalformedProtobuf() {
        AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed = new AtomicReference<>();
        AtomicBoolean processed = new AtomicBoolean();
        RawItemKafkaListener listener = new RawItemKafkaListener(
                new RawItemDiscoveredMapper(),
                rawItem -> {
                    processed.set(true);
                    throw new AssertionError("processor must not run for malformed transport data");
                });

        assertThrows(IllegalArgumentException.class, () ->
                listener.receive(RAW_ITEM_ID, new byte[] {0x0A, 0x05, 0x01}, 7L, 2, TOPIC, consumer(committed)));

        assertNull(committed.get());
        assertFalse(processed.get());
    }

    /**
     * Leave offset uncommitted and skip processing when Kafka key does not match payload.
     */
    @Test
    void shouldLeaveOffsetUncommittedAndSkipProcessingWhenKafkaKeyDoesNotMatchPayload() {
        AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed = new AtomicReference<>();
        AtomicBoolean processed = new AtomicBoolean();
        RawItemKafkaListener listener = new RawItemKafkaListener(
                new RawItemDiscoveredMapper(),
                rawItem -> {
                    processed.set(true);
                    throw new AssertionError("processor must not run for key mismatch");
                });

        assertThrows(IllegalArgumentException.class, () ->
                listener.receive("different-raw-id", event().toByteArray(), 7L, 2, TOPIC, consumer(committed)));

        assertNull(committed.get());
        assertFalse(processed.get());
    }

    /**
     * Leave offset uncommitted and skip processing for invalid mapped domain data.
     */
    @Test
    void shouldLeaveOffsetUncommittedAndSkipProcessingForInvalidMappedDomainData() {
        AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed = new AtomicReference<>();
        AtomicBoolean processed = new AtomicBoolean();
        RawItemKafkaListener listener = new RawItemKafkaListener(
                new RawItemDiscoveredMapper(),
                rawItem -> {
                    processed.set(true);
                    throw new AssertionError("processor must not run for invalid domain data");
                });
        RawItemDiscovered invalid = event().toBuilder()
                .setUrl("ftp://example.test/jobs/1")
                .build();

        assertThrows(IllegalArgumentException.class, () ->
                listener.receive(RAW_ITEM_ID, invalid.toByteArray(), 7L, 2, TOPIC, consumer(committed)));

        assertNull(committed.get());
        assertFalse(processed.get());
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
}
