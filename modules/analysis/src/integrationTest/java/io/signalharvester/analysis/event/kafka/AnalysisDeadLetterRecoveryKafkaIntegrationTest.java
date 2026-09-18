package io.signalharvester.analysis.event.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Replaces;
import io.signalharvester.analysis.application.DeadLetterRecovery;
import io.signalharvester.analysis.application.DeadLetterRecoveryException;
import io.signalharvester.analysis.application.RawItemProcessingResult;
import io.signalharvester.analysis.application.RawItemProcessingService;
import io.signalharvester.analysis.application.RawItemProcessingStatus;
import io.signalharvester.analysis.application.RawItemProcessor;
import io.signalharvester.analysis.model.DiscoveredRawItem;
import io.signalharvester.events.collection.v1.KeywordAnalysisSettings;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import io.signalharvester.events.common.v1.EventEnvelope;
import io.signalharvester.events.failure.v1.DeadLetterEvent;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Verifies controlled Analysis DLQ inspection and owner-local replay through
 * {@link KafkaAnalysisDeadLetterRecoveryService} against a real Kafka broker.
 */
@Testcontainers(disabledWithoutDocker = true)
class AnalysisDeadLetterRecoveryKafkaIntegrationTest {

    private static final String SPEC_NAME = "analysis-dead-letter-recovery-kafka";
    private static final String RAW_TOPIC = "signalharvester.collection.raw-item-discovered.v1.recovery-it";
    private static final String DEAD_LETTER_TOPIC = "signalharvester.analysis.raw-item-dead-letter.v1.recovery-it";
    private static final String GROUP = "signalharvester-analysis-recovery-it";
    private static final String RAW_ITEM_ID = "raw-recovery-it";

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    private ApplicationContext context;
    private KafkaProducer<String, byte[]> producer;

    @BeforeEach
    void setUp() throws Exception {
        createTopics();
        context = ApplicationContext.run(Map.ofEntries(
                Map.entry("spec.name", SPEC_NAME),
                Map.entry("datasources.default.enabled", false),
                Map.entry("flyway.datasources.default.enabled", false),
                Map.entry("kafka.enabled", false),
                Map.entry("kafka.bootstrap.servers", KAFKA.getBootstrapServers()),
                Map.entry("signalharvester.analysis.enabled", false),
                Map.entry("signalharvester.analysis.consumer-group", GROUP),
                Map.entry("signalharvester.analysis.kafka-reliability.dead-letter-topic", DEAD_LETTER_TOPIC),
                Map.entry("signalharvester.analysis.kafka-reliability.replay-read-timeout", "2s"),
                Map.entry("signalharvester.analysis.kafka-reliability.replay-max-concurrency", 1),
                Map.entry("signalharvester.kafka.raw-item-discovered-topic", RAW_TOPIC),
                Map.entry("signalharvester.analysis.keyword-rules.keywords", List.of("java")),
                Map.entry("signalharvester.analysis.keyword-rules.minimum-matches", 1)));
        producer = new KafkaProducer<>(producerProperties());
    }

    @AfterEach
    void tearDown() {
        if (producer != null) {
            producer.close();
        }
        if (context != null) {
            context.close();
        }
    }

    /** Inspect and replay one confirmed Analysis DLQ record without publishing back to the shared raw topic. */
    @Test
    void shouldReplayThroughAnalysisOnlyWithoutRepublishingSourceEvent() throws Exception {
        RawItemDiscovered source = rawEvent();
        String deadLetterId = GROUP + ":" + RAW_TOPIC + ":0:41";
        DeadLetterEvent deadLetter = deadLetter(deadLetterId, source);
        var metadata = producer.send(new ProducerRecord<>(DEAD_LETTER_TOPIC, deadLetterId, deadLetter.toByteArray())).get();
        producer.flush();

        DeadLetterRecovery recovery = context.getBean(DeadLetterRecovery.class);
        DeadLetterRecovery.Inspection inspection = recovery.inspect(metadata.partition(), metadata.offset());
        assertEquals(deadLetterId, inspection.deadLetterId());
        assertEquals(RAW_TOPIC, inspection.sourceTopic());
        assertEquals(source.toByteArray().length, inspection.sourcePayloadBytes());

        DeadLetterRecoveryException mismatch = assertThrows(
                DeadLetterRecoveryException.class,
                () -> recovery.replay(metadata.partition(), metadata.offset(), "wrong-id"));
        assertEquals(DeadLetterRecoveryException.Reason.CONFIRMATION_FAILED, mismatch.reason());

        recovery.replay(metadata.partition(), metadata.offset(), deadLetterId);

        RecordingRawItemProcessor processor = context.getBean(RecordingRawItemProcessor.class);
        DiscoveredRawItem replayed = processor.lastItem().get();
        assertNotNull(replayed);
        assertEquals(RAW_ITEM_ID, replayed.rawItemId());
        assertEquals(List.of("java"), replayed.analysisSettings().keywords());
        assertEquals(0L, topicEndOffset(RAW_TOPIC));
    }

    /** Reject a real DLQ record whose stored consumer group does not match the current Analysis owner. */
    @Test
    void shouldRejectDeadLetterFromDifferentConsumerGroup() throws Exception {
        RawItemDiscovered source = rawEvent();
        String foreignGroup = "foreign-analysis-group";
        String deadLetterId = foreignGroup + ":" + RAW_TOPIC + ":0:42";
        DeadLetterEvent deadLetter = DeadLetterEvent.newBuilder()
                .setDeadLetterId(deadLetterId)
                .setConsumer("analysis")
                .setConsumerGroup(foreignGroup)
                .setSourceTopic(RAW_TOPIC)
                .setSourcePartition(0)
                .setSourceOffset(42)
                .setSourceKey(RAW_ITEM_ID)
                .setSourcePayload(ByteString.copyFrom(source.toByteArray()))
                .setFailureType("java.lang.IllegalStateException")
                .setFailureMessage("foreign group")
                .setAttempts(1)
                .setRetryable(false)
                .build();
        var metadata = producer.send(new ProducerRecord<>(DEAD_LETTER_TOPIC, deadLetterId, deadLetter.toByteArray())).get();
        producer.flush();

        DeadLetterRecoveryException failure = assertThrows(
                DeadLetterRecoveryException.class,
                () -> context.getBean(DeadLetterRecovery.class).inspect(metadata.partition(), metadata.offset()));
        assertEquals(DeadLetterRecoveryException.Reason.INVALID_RECORD, failure.reason());
    }

    private static DeadLetterEvent deadLetter(String deadLetterId, RawItemDiscovered source) {
        return DeadLetterEvent.newBuilder()
                .setDeadLetterId(deadLetterId)
                .setConsumer("analysis")
                .setConsumerGroup(GROUP)
                .setSourceTopic(RAW_TOPIC)
                .setSourcePartition(0)
                .setSourceOffset(41)
                .setSourceKey(RAW_ITEM_ID)
                .setSourcePayload(ByteString.copyFrom(source.toByteArray()))
                .setFailureType("java.lang.IllegalStateException")
                .setFailureMessage("temporary analysis failure")
                .setAttempts(3)
                .setRetryable(true)
                .build();
    }

    private static RawItemDiscovered rawEvent() {
        return RawItemDiscovered.newBuilder()
                .setEnvelope(EventEnvelope.newBuilder()
                        .setEventId("raw-event-recovery-it")
                        .setEventType("collection.raw-item-discovered.v1")
                        .setOccurredAt(Timestamp.newBuilder().setSeconds(1_700_000_000L).build())
                        .setCorrelationId("run-recovery-it")
                        .setProducer("collection")
                        .setSchemaVersion("v1")
                        .build())
                .setRawItemId(RAW_ITEM_ID)
                .setSourceId("source-recovery-it")
                .setMonitoringProfileId("profile-recovery-it")
                .setInformationCategory("JOB")
                .setTitle("Senior Java Engineer")
                .setUrl("https://example.test/jobs/recovery")
                .setContent("Java backend")
                .setContentType("text/plain")
                .setAnalysisSettings(KeywordAnalysisSettings.newBuilder()
                        .addKeywords("java")
                        .setMinimumMatches(1)
                        .build())
                .build();
    }

    private static void createTopics() throws InterruptedException, ExecutionException {
        try (Admin admin = Admin.create(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            for (String topic : List.of(RAW_TOPIC, DEAD_LETTER_TOPIC)) {
                try {
                    admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
                } catch (ExecutionException failure) {
                    if (!(failure.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
                        throw failure;
                    }
                }
            }
        }
    }

    private static long topicEndOffset(String topic) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", KAFKA.getBootstrapServers());
        properties.put("key.deserializer", StringDeserializer.class.getName());
        properties.put("value.deserializer", ByteArrayDeserializer.class.getName());
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties)) {
            TopicPartition partition = new TopicPartition(topic, 0);
            return consumer.endOffsets(List.of(partition), Duration.ofSeconds(5)).get(partition);
        }
    }

    private static Properties producerProperties() {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", KAFKA.getBootstrapServers());
        properties.put("key.serializer", StringSerializer.class.getName());
        properties.put("value.serializer", ByteArraySerializer.class.getName());
        properties.put("acks", "all");
        return properties;
    }

    @Singleton
    @Replaces(RawItemProcessingService.class)
    @Requires(property = "spec.name", value = SPEC_NAME)
    static final class RecordingRawItemProcessor implements RawItemProcessor {
        private final AtomicReference<DiscoveredRawItem> lastItem = new AtomicReference<>();

        @Override
        public RawItemProcessingResult process(DiscoveredRawItem rawItem) {
            lastItem.set(rawItem);
            return new RawItemProcessingResult(
                    RawItemProcessingStatus.ANALYZED,
                    "a".repeat(64),
                    "analysis-recovery-it",
                    "signalharvester.analysis.item-analyzed.v1");
        }

        AtomicReference<DiscoveredRawItem> lastItem() {
            return lastItem;
        }
    }
}
