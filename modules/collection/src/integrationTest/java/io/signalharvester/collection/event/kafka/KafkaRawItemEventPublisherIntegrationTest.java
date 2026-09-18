package io.signalharvester.collection.event.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micronaut.context.ApplicationContext;
import io.signalharvester.collection.event.RawItemEventPublisher;
import io.signalharvester.collection.event.RawItemPublicationContext;
import io.signalharvester.collection.event.RawItemPublicationResult;
import io.signalharvester.collection.source.ExtractedSourceItem;
import io.signalharvester.configuration.api.MonitoringProfileAnalysisSettings;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Verifies {@link KafkaRawItemEventPublisher} against a real Kafka-compatible broker, including serialized
 * {@code RawItemDiscovered} delivery, configured topic selection, and raw-item message keys.
 *
 * <p>Related specifications: {@code backend-rss-atom-extraction}, {@code backend-event-contracts}.</p>
 *
 * <p>Features: {@code EVENTING.PIPELINE}, {@code CONTRACTS.KAFKA_PROTOBUF}.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class KafkaRawItemEventPublisherIntegrationTest {

    private static final String TOPIC = "signalharvester.collection.raw-item-discovered.v1.test";
    private static final String RAW_ITEM_ID = "raw-kafka-1";
    private static final String RUN_ID = "run-kafka-1";
    private static final String PROFILE_ID = "profile-kafka-1";
    private static final String CONTENT = "Java Kafka PostgreSQL";
    private static final SourceId SOURCE_ID = SourceId.of(
            UUID.fromString("00000000-0000-0000-0000-000000000401"));

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    private ApplicationContext context;

    @BeforeEach
    void setUp() throws Exception {
        createTopic();
        context = ApplicationContext.run(Map.<String, Object>ofEntries(
                Map.entry("kafka.enabled", true),
                Map.entry("kafka.bootstrap.servers", KAFKA.getBootstrapServers()),
                Map.entry("kafka.producers.collection-raw-items.key.serializer",
                        "org.apache.kafka.common.serialization.StringSerializer"),
                Map.entry("kafka.producers.collection-raw-items.value.serializer",
                        "org.apache.kafka.common.serialization.ByteArraySerializer"),
                Map.entry("kafka.producers.collection-raw-items.acks", "all"),
                Map.entry("kafka.producers.collection-raw-items.enable.idempotence", true),
                Map.entry("signalharvester.kafka.raw-item-discovered-topic", TOPIC)));
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    /**
     * Publish and decode raw item through Kafka.
     */
    @Test
    void shouldPublishAndDecodeRawItemThroughKafka() throws Exception {
        RawItemEventPublisher publisher = context.getBean(RawItemEventPublisher.class);
        RawItemPublicationResult publication = publisher.publish(item(), new RawItemPublicationContext(
                RAW_ITEM_ID,
                RUN_ID,
                PROFILE_ID,
                "JOB",
                new MonitoringProfileAnalysisSettings(List.of("java", "kafka"), 1),
                Optional.empty()));

        ConsumerRecord<String, byte[]> record = consumeOne();
        RawItemDiscovered decoded = RawItemDiscovered.parseFrom(record.value());

        assertEquals(TOPIC, record.topic());
        assertEquals(publication.rawItemId(), record.key());
        assertEquals(publication.eventId(), decoded.getEnvelope().getEventId());
        assertEquals(publication.rawItemId(), decoded.getRawItemId());
        assertEquals(RUN_ID, decoded.getEnvelope().getCorrelationId());
        assertEquals(PROFILE_ID, decoded.getMonitoringProfileId());
        assertEquals("JOB", decoded.getInformationCategory());
        assertEquals(item().sourceId().value().toString(), decoded.getSourceId());
        assertEquals(item().url().toString(), decoded.getUrl());
        assertEquals(CONTENT, decoded.getContent());
    }

    private ConsumerRecord<String, byte[]> consumeOne() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "collection-publisher-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());

        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(TOPIC));
            Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(250))) {
                    return record;
                }
            }
        }
        throw new AssertionError("No Kafka record received from " + TOPIC);
    }

    private static void createTopic() throws InterruptedException, ExecutionException {
        try (Admin admin = Admin.create(Map.<String, Object>of(
                "bootstrap.servers", KAFKA.getBootstrapServers()))) {
            try {
                admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
            } catch (ExecutionException failure) {
                if (!(failure.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
                    throw failure;
                }
            }
        }
    }

    private static ExtractedSourceItem item() {
        return new ExtractedSourceItem(
                SOURCE_ID,
                URI.create("https://example.test/jobs/401"),
                Optional.of("job-401"),
                Optional.of("Java Kafka PostgreSQL"),
                CONTENT,
                "text/plain; charset=UTF-8",
                Optional.empty(),
                Instant.parse("2026-09-10T14:00:00Z"),
                CONTENT.getBytes(StandardCharsets.UTF_8));
    }
}
