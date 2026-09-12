package io.signalharvester.analysis.event.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class RawItemKafkaListenerTest {

    private static final String TOPIC = "raw-items";
    private static final String RAW_ITEM_ID = "raw-01";

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

    private static RawItemDiscovered event() {
        return RawItemDiscovered.newBuilder()
                .setEnvelope(EventEnvelope.newBuilder()
                        .setEventId("source-event-01")
                        .setEventType("collection.raw-item-discovered.v1")
                        .setOccurredAt(Timestamp.newBuilder().setSeconds(1_789_000_000L).build())
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
