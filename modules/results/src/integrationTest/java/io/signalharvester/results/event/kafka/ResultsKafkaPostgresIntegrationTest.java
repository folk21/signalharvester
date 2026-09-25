package io.signalharvester.results.event.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Timestamp;
import io.micronaut.context.ApplicationContext;
import io.signalharvester.events.analysis.v1.ItemAnalyzed;
import io.signalharvester.events.analysis.v1.ItemRejected;
import io.signalharvester.events.common.v1.EventEnvelope;
import io.signalharvester.events.failure.v1.DeadLetterEvent;
import io.signalharvester.results.application.DeadLetterRecovery;
import io.signalharvester.results.application.DeadLetterRecoveryException;
import io.signalharvester.testing.Await;
import io.signalharvester.testing.KafkaContainerSupport;
import io.signalharvester.testing.PostgresContainerSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies the real {@link AnalysisOutcomeKafkaListener} -> {@link io.signalharvester.results.application.ResultProjectionService}
 * -> PostgreSQL path for idempotent analyzed and rejected Results projections.
 *
 * <p>Related specifications: {@code backend-results-persistence}, {@code backend-reliability-failure-handling}.</p>
 *
 * <p>Features: {@code RESULTS.MATERIALIZATION}, {@code RELIABILITY.IDEMPOTENCY}, {@code RELIABILITY.DEAD_LETTER}.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class ResultsKafkaPostgresIntegrationTest {

    private static final String ANALYZED_TOPIC = "signalharvester.analysis.item-analyzed.v1.results-it";
    private static final String REJECTED_TOPIC = "signalharvester.analysis.item-rejected.v1.results-it";
    private static final String RESULTS_GROUP = "signalharvester-results-it";
    private static final String DEAD_LETTER_TOPIC = "signalharvester.results.dead-letter.v1.results-it";
    private static final String PROFILE_ID = "profile-results-it";
    private static final String SOURCE_ID = "source-results-it";
    private static final String NORMALIZED_ITEM_ID = "b".repeat(64);
    private static final String RAW_ITEM_ID = "raw-results-it";
    private static final String SOURCE_EVENT_ID = "source-event-results-it";

    @Container
    private static final PostgreSQLContainer POSTGRES = PostgresContainerSupport.create();

    @Container
    private static final KafkaContainer KAFKA = KafkaContainerSupport.create();

    private ApplicationContext context;
    private KafkaProducer<String, byte[]> producer;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        createTopics();
        context = ApplicationContext.run(Map.ofEntries(
                Map.entry("datasources.default.url", POSTGRES.getJdbcUrl()),
                Map.entry("datasources.default.username", POSTGRES.getUsername()),
                Map.entry("datasources.default.password", POSTGRES.getPassword()),
                Map.entry("datasources.default.driver-class-name", "org.postgresql.Driver"),
                Map.entry("flyway.datasources.default.enabled", true),
                Map.entry("flyway.datasources.default.locations[0]", "classpath:db/migration/results"),
                Map.entry("kafka.enabled", true),
                Map.entry("kafka.bootstrap.servers", KAFKA.getBootstrapServers()),
                Map.entry("kafka.consumers." + RESULTS_GROUP + ".key.deserializer",
                        "org.apache.kafka.common.serialization.StringDeserializer"),
                Map.entry("kafka.consumers." + RESULTS_GROUP + ".value.deserializer",
                        "org.apache.kafka.common.serialization.ByteArrayDeserializer"),
                Map.entry("kafka.producers.results-dead-letter.key.serializer",
                        "org.apache.kafka.common.serialization.StringSerializer"),
                Map.entry("kafka.producers.results-dead-letter.value.serializer",
                        "org.apache.kafka.common.serialization.ByteArraySerializer"),
                Map.entry("kafka.producers.results-dead-letter.acks", "all"),
                Map.entry("kafka.producers.results-dead-letter.enable.idempotence", true),
                Map.entry("signalharvester.results.enabled", true),
                Map.entry("signalharvester.results.consumer-group", RESULTS_GROUP),
                Map.entry("signalharvester.results.kafka-reliability.max-attempts", 1),
                Map.entry("signalharvester.results.kafka-reliability.retry-backoff", "0ms"),
                Map.entry("signalharvester.results.kafka-reliability.dead-letter-topic", DEAD_LETTER_TOPIC),
                Map.entry("signalharvester.kafka.item-analyzed-topic", ANALYZED_TOPIC),
                Map.entry("signalharvester.kafka.item-rejected-topic", REJECTED_TOPIC)));
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

    /**
     * Upsert repeated analyzed publications into one logical result while replacing attributes and tags.
     */
    @Test
    void shouldMaterializeAnalyzedEventsIdempotently() throws Exception {
        ItemAnalyzed firstEvent = analyzedEvent("analysis-event-01", 75, List.of("java", "kafka"));
        send(ANALYZED_TOPIC, NORMALIZED_ITEM_ID, firstEvent);
        awaitSqlValue("SELECT count(*) FROM results.analyzed_items", 1L);
        awaitSqlValue("SELECT count(*) FROM results.live_result_cursors", 1L);
        long firstLiveCursor = sqlLong("SELECT live_event_id FROM results.live_result_cursors");
        assertEquals(2L, sqlLong("SELECT count(*) FROM results.analyzed_item_tags"));
        assertEquals("MATCHED_KEYWORDS", sqlString("SELECT classification FROM results.analyzed_items"));

        send(ANALYZED_TOPIC, NORMALIZED_ITEM_ID, analyzedEvent("analysis-event-01", 76, List.of("java", "kafka")));
        awaitSqlValue("SELECT score FROM results.analyzed_items", 76L);
        assertEquals(firstLiveCursor, sqlLong("SELECT live_event_id FROM results.live_result_cursors"));

        send(ANALYZED_TOPIC, NORMALIZED_ITEM_ID, analyzedEvent("analysis-event-02", 90, List.of("java")));
        awaitSqlValue("SELECT score FROM results.analyzed_items", 90L);
        assertTrue(sqlLong("SELECT live_event_id FROM results.live_result_cursors") > firstLiveCursor);

        assertEquals(1L, sqlLong("SELECT count(*) FROM results.analyzed_items"));
        assertEquals("analysis-event-02", sqlString("SELECT analysis_event_id FROM results.analyzed_items"));
        assertEquals(1L, sqlLong("SELECT count(*) FROM results.analyzed_item_tags"));
        assertEquals("java", sqlString("SELECT tag FROM results.analyzed_item_tags"));
        assertEquals(1L, sqlLong("SELECT count(*) FROM results.analyzed_item_attributes"));
        assertEquals("Remote", sqlString("SELECT attribute_value FROM results.analyzed_item_attributes"));
    }

    /**
     * Upsert retries of the same rejected source event without creating duplicate rejection rows.
     */
    @Test
    void shouldMaterializeRejectedEventsIdempotently() throws Exception {
        send(REJECTED_TOPIC, NORMALIZED_ITEM_ID, rejectedEvent("rejection-event-01", "Already accepted"));
        awaitSqlValue("SELECT count(*) FROM results.rejected_items", 1L);

        send(REJECTED_TOPIC, NORMALIZED_ITEM_ID, rejectedEvent("rejection-event-02", "Duplicate rediscovery"));
        awaitSqlString("SELECT analysis_event_id FROM results.rejected_items", "rejection-event-02");

        assertEquals(1L, sqlLong("SELECT count(*) FROM results.rejected_items"));
        assertEquals("Duplicate rediscovery", sqlString("SELECT explanation FROM results.rejected_items"));
        assertEquals(NORMALIZED_ITEM_ID, sqlString("SELECT normalized_item_id FROM results.rejected_items"));
    }

    /** Inspect and replay one Results DLQ record through Results only, preserving projection idempotency. */
    @Test
    void shouldInspectAndReplayDeadLetterThroughOwningResultsPath() throws Exception {
        ItemAnalyzed source = analyzedEvent("analysis-event-recovery", 93, List.of("java"));
        String deadLetterId = RESULTS_GROUP + ":" + ANALYZED_TOPIC + ":0:123";
        DeadLetterEvent deadLetter = DeadLetterEvent.newBuilder()
                .setDeadLetterId(deadLetterId)
                .setConsumer("results")
                .setConsumerGroup(RESULTS_GROUP)
                .setSourceTopic(ANALYZED_TOPIC)
                .setSourcePartition(0)
                .setSourceOffset(123)
                .setSourceKey(NORMALIZED_ITEM_ID)
                .setSourcePayload(com.google.protobuf.ByteString.copyFrom(source.toByteArray()))
                .setFailureType("java.lang.IllegalStateException")
                .setFailureMessage("temporary projection failure")
                .setAttempts(1)
                .setRetryable(true)
                .build();
        var metadata = producer.send(new ProducerRecord<>(DEAD_LETTER_TOPIC, deadLetterId, deadLetter.toByteArray())).get();
        producer.flush();

        long sourceTopicEndOffsetBeforeReplay = topicEndOffset(ANALYZED_TOPIC);

        DeadLetterRecovery recovery = context.getBean(DeadLetterRecovery.class);
        DeadLetterRecovery.Inspection inspection = recovery.inspect(metadata.partition(), metadata.offset());
        assertEquals(deadLetterId, inspection.deadLetterId());
        assertEquals(ANALYZED_TOPIC, inspection.sourceTopic());
        assertEquals(source.toByteArray().length, inspection.sourcePayloadBytes());

        DeadLetterRecoveryException mismatch = assertThrows(
                DeadLetterRecoveryException.class,
                () -> recovery.replay(metadata.partition(), metadata.offset(), "wrong-id"));
        assertEquals(DeadLetterRecoveryException.Reason.CONFIRMATION_FAILED, mismatch.reason());

        recovery.replay(metadata.partition(), metadata.offset(), deadLetterId);
        awaitSqlValue("SELECT count(*) FROM results.analyzed_items", 1L);
        assertEquals(sourceTopicEndOffsetBeforeReplay, topicEndOffset(ANALYZED_TOPIC));
        assertEquals(93L, sqlLong("SELECT score FROM results.analyzed_items"));
        long firstCursor = sqlLong("SELECT live_event_id FROM results.live_result_cursors");

        recovery.replay(metadata.partition(), metadata.offset(), deadLetterId);
        assertEquals(1L, sqlLong("SELECT count(*) FROM results.analyzed_items"));
        assertEquals(firstCursor, sqlLong("SELECT live_event_id FROM results.live_result_cursors"));
    }

    /** Reject a real Results DLQ record whose consumer owner does not match the Results module. */
    @Test
    void shouldRejectDeadLetterFromDifferentConsumer() throws Exception {
        ItemAnalyzed source = analyzedEvent("analysis-event-foreign-consumer", 91, List.of("java"));
        long sourceOffset = 124L;
        String deadLetterId = RESULTS_GROUP + ":" + ANALYZED_TOPIC + ":0:" + sourceOffset;
        DeadLetterEvent deadLetter = DeadLetterEvent.newBuilder()
                .setDeadLetterId(deadLetterId)
                .setConsumer("analysis")
                .setConsumerGroup(RESULTS_GROUP)
                .setSourceTopic(ANALYZED_TOPIC)
                .setSourcePartition(0)
                .setSourceOffset(sourceOffset)
                .setSourceKey(NORMALIZED_ITEM_ID)
                .setSourcePayload(com.google.protobuf.ByteString.copyFrom(source.toByteArray()))
                .setFailureType("java.lang.IllegalStateException")
                .setFailureMessage("foreign consumer")
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

    /** Dead-letter a poison record and continue materializing the following record on the same partition. */
    @Test
    void shouldDeadLetterPoisonRecordAndContinuePartition() throws Exception {
        var poisonMetadata = producer
                .send(new ProducerRecord<>(ANALYZED_TOPIC, NORMALIZED_ITEM_ID, new byte[] {(byte) 0x80}))
                .get();
        send(ANALYZED_TOPIC, NORMALIZED_ITEM_ID, analyzedEvent("analysis-event-after-poison", 88, List.of("java")));

        awaitSqlValue("SELECT count(*) FROM results.analyzed_items", 1L);
        assertEquals(88L, sqlLong("SELECT score FROM results.analyzed_items"));

        String expectedDeadLetterId = RESULTS_GROUP + ":" + ANALYZED_TOPIC + ":"
                + poisonMetadata.partition() + ":" + poisonMetadata.offset();
        ConsumerRecord<String, byte[]> deadLetterRecord = consumeOne(DEAD_LETTER_TOPIC, expectedDeadLetterId);
        DeadLetterEvent deadLetter = DeadLetterEvent.parseFrom(deadLetterRecord.value());
        assertEquals(expectedDeadLetterId, deadLetter.getDeadLetterId());
        assertEquals(poisonMetadata.partition(), deadLetter.getSourcePartition());
        assertEquals(poisonMetadata.offset(), deadLetter.getSourceOffset());
        assertEquals("results", deadLetter.getConsumer());
        assertEquals(RESULTS_GROUP, deadLetter.getConsumerGroup());
        assertEquals(ANALYZED_TOPIC, deadLetter.getSourceTopic());
        assertEquals(NORMALIZED_ITEM_ID, deadLetter.getSourceKey());
        assertEquals(1, deadLetter.getAttempts());
        assertFalse(deadLetter.getRetryable());
    }

    private static ItemAnalyzed analyzedEvent(String analysisEventId, int score, List<String> tags) {
        return ItemAnalyzed.newBuilder()
                .setEnvelope(envelope(analysisEventId, "analysis.item-analyzed.v1"))
                .setSourceEventId(SOURCE_EVENT_ID)
                .setRawItemId(RAW_ITEM_ID)
                .setNormalizedItemId(NORMALIZED_ITEM_ID)
                .setSourceId(SOURCE_ID)
                .setMonitoringProfileId(PROFILE_ID)
                .setInformationCategory("JOB")
                .setExternalId("external-job-01")
                .setTitle("Senior Java Engineer")
                .setUrl("https://example.test/jobs/1")
                .setNormalizedContent("Java Kafka PostgreSQL")
                .setContentType("text/plain")
                .putAttributes("location", "Remote")
                .setRelevant(true)
                .setClassification("MATCHED_KEYWORDS")
                .setScore(score)
                .addAllTags(tags)
                .setExplanation("Matched deterministic keywords")
                .setAnalyzer("keyword-v1")
                .setPublishedAt(Timestamp.newBuilder().setSeconds(1_700_000_000L).build())
                .build();
    }

    private static ItemRejected rejectedEvent(String analysisEventId, String explanation) {
        return ItemRejected.newBuilder()
                .setEnvelope(envelope(analysisEventId, "analysis.item-rejected.v1"))
                .setSourceEventId(SOURCE_EVENT_ID)
                .setRawItemId(RAW_ITEM_ID)
                .setNormalizedItemId(NORMALIZED_ITEM_ID)
                .setSourceId(SOURCE_ID)
                .setMonitoringProfileId(PROFILE_ID)
                .setInformationCategory("JOB")
                .setReasonCode("DUPLICATE")
                .setExplanation(explanation)
                .build();
    }

    private static EventEnvelope envelope(String eventId, String eventType) {
        return EventEnvelope.newBuilder()
                .setEventId(eventId)
                .setEventType(eventType)
                .setOccurredAt(Timestamp.newBuilder().setSeconds(1_700_000_100L).build())
                .setCorrelationId("run-results-it")
                .setTraceparent("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01")
                .setProducer("analysis")
                .setSchemaVersion("v1")
                .build();
    }

    private void send(String topic, String key, com.google.protobuf.MessageLite event) throws Exception {
        producer.send(new ProducerRecord<>(topic, key, event.toByteArray())).get();
        producer.flush();
    }

    private static ConsumerRecord<String, byte[]> consumeOne(String topic, String expectedKey) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "results-dlq-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
            while (Instant.now().isBefore(deadline)) {
                var records = consumer.poll(Duration.ofMillis(250));
                for (ConsumerRecord<String, byte[]> record : records) {
                    if (expectedKey.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("Timed out waiting for Kafka record with key " + expectedKey + " on " + topic);
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

    private static void awaitSqlValue(String sql, long expected) throws Exception {
        Await.until(
                "SQL value " + expected + " for " + sql,
                Duration.ofSeconds(20),
                Duration.ofMillis(100),
                () -> sqlLong(sql),
                actual -> actual == expected);
    }

    private static void awaitSqlString(String sql, String expected) throws Exception {
        Await.until(
                "SQL value " + expected + " for " + sql,
                Duration.ofSeconds(20),
                Duration.ofMillis(100),
                () -> sqlStringOrNull(sql),
                expected::equals);
    }

    private static long sqlLong(String sql) throws Exception {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static String sqlString(String sql) throws Exception {
        String value = sqlStringOrNull(sql);
        if (value == null) {
            throw new AssertionError("Expected SQL query to return a non-null value: " + sql);
        }
        return value;
    }

    private static String sqlStringOrNull(String sql) throws Exception {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                return null;
            }
            return result.getString(1);
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void createTopics() throws InterruptedException, ExecutionException {
        try (Admin admin = Admin.create(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            for (String topic : List.of(ANALYZED_TOPIC, REJECTED_TOPIC, DEAD_LETTER_TOPIC)) {
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

    private static void resetDatabase() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS results CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }
}
