package io.signalharvester.eventobservation.event.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.micronaut.context.ApplicationContext;
import io.signalharvester.eventobservation.application.DeadLetterRecovery;
import io.signalharvester.eventobservation.application.DeadLetterRecoveryException;
import io.signalharvester.eventobservation.application.EventObservationCriteria;
import io.signalharvester.eventobservation.application.EventObservationQuery;
import io.signalharvester.eventobservation.application.ProcessingFlowQuery;
import io.signalharvester.eventobservation.persistence.EventObservationPersistenceException;
import io.signalharvester.eventobservation.persistence.EventObservationRepository;
import io.signalharvester.events.analysis.v1.ItemAnalyzed;
import io.signalharvester.events.analysis.v1.ItemRejected;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import io.signalharvester.events.common.v1.EventEnvelope;
import io.signalharvester.events.failure.v1.DeadLetterEvent;
import io.signalharvester.testing.Await;
import io.signalharvester.testing.KafkaContainerSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
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
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies Kafka decoding, idempotent persistence, and bounded retention for {@link EventObservationKafkaListener}.
 *
 * <p>Features: {@code DIAGNOSTICS.EVENT_OBSERVATION}, {@code RELIABILITY.IDEMPOTENCY}, {@code RELIABILITY.DEAD_LETTER}.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class EventObservationKafkaPostgresIntegrationTest {

    private static final String RAW_TOPIC = "signalharvester.collection.raw-item-discovered.v1.observation-it";
    private static final String ANALYZED_TOPIC = "signalharvester.analysis.item-analyzed.v1.observation-it";
    private static final String REJECTED_TOPIC = "signalharvester.analysis.item-rejected.v1.observation-it";
    private static final String GROUP = "signalharvester-event-observation-it";
    private static final String DEAD_LETTER_TOPIC = "signalharvester.event-observation.dead-letter.v1.it";
    private static final String NORMALIZED_ID = "c".repeat(64);
    private static final String FLOW_RUN_ID = "run-jdbi-flow";
    private static final String FLOW_RAW_ITEM_ID = "raw-jdbi-flow";
    private static final String FLOW_NORMALIZED_ID = "d".repeat(64);
    private static final String FLOW_RAW_EVENT_ID = "raw-event-jdbi-flow";
    private static final String FLOW_ANALYZED_EVENT_ID = "analyzed-event-jdbi-flow";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("signalharvester")
            .withUsername("signalharvester")
            .withPassword("signalharvester");

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
                Map.entry("flyway.datasources.default.locations[0]", "classpath:db/migration/event_observation"),
                Map.entry("kafka.enabled", true),
                Map.entry("kafka.bootstrap.servers", KAFKA.getBootstrapServers()),
                Map.entry("kafka.consumers." + GROUP + ".key.deserializer",
                        "org.apache.kafka.common.serialization.StringDeserializer"),
                Map.entry("kafka.consumers." + GROUP + ".value.deserializer",
                        "org.apache.kafka.common.serialization.ByteArrayDeserializer"),
                Map.entry("kafka.producers.event-observation-dead-letter.key.serializer",
                        "org.apache.kafka.common.serialization.StringSerializer"),
                Map.entry("kafka.producers.event-observation-dead-letter.value.serializer",
                        "org.apache.kafka.common.serialization.ByteArraySerializer"),
                Map.entry("kafka.producers.event-observation-dead-letter.acks", "all"),
                Map.entry("kafka.producers.event-observation-dead-letter.enable.idempotence", true),
                Map.entry("signalharvester.event-observation.enabled", true),
                Map.entry("signalharvester.event-observation.consumer-group", GROUP),
                Map.entry("signalharvester.event-observation.kafka-reliability.max-attempts", 3),
                Map.entry("signalharvester.event-observation.kafka-reliability.retry-backoff", "0ms"),
                Map.entry("signalharvester.event-observation.kafka-reliability.dead-letter-topic", DEAD_LETTER_TOPIC),
                Map.entry("signalharvester.event-observation.retention.max-events", 2),
                Map.entry("signalharvester.event-observation.retention.max-age", "24h"),
                Map.entry("signalharvester.kafka.raw-item-discovered-topic", RAW_TOPIC),
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

    /** Record supported event families once by event id and retain only the configured newest history bound. */
    @Test
    void shouldPersistDecodedEventsIdempotentlyAndPruneToBound() throws Exception {
        RawItemDiscovered raw = rawEvent();
        send(RAW_TOPIC, "raw-1", raw);
        awaitSqlValue("SELECT count(*) FROM event_observation.observed_events", 1L);

        send(RAW_TOPIC, "raw-1", raw);
        Thread.sleep(200);
        assertEquals(1L, sqlLong("SELECT count(*) FROM event_observation.observed_events"));

        send(ANALYZED_TOPIC, NORMALIZED_ID, analyzedEvent());
        awaitSqlValue("SELECT count(*) FROM event_observation.observed_events", 2L);
        assertEquals("MATCHED", sqlString("SELECT classification FROM event_observation.observed_events WHERE event_id='analyzed-event'"));
        assertEquals(90L, sqlLong("SELECT score FROM event_observation.observed_events WHERE event_id='analyzed-event'"));

        send(REJECTED_TOPIC, NORMALIZED_ID, rejectedEvent());
        awaitSqlValue("SELECT count(*) FROM event_observation.observed_events", 2L);

        assertEquals(0L, sqlLong("SELECT count(*) FROM event_observation.observed_events WHERE event_id='raw-event'"));
        assertEquals("DUPLICATE", sqlString("SELECT reason_code FROM event_observation.observed_events WHERE event_id='rejected-event'"));
        assertTrue(sqlLong("SELECT max(observation_id) FROM event_observation.observed_events") >= 3L);

        EventObservationQuery query = context.getBean(EventObservationQuery.class);
        var filtered = query.recent(new EventObservationCriteria(
                Optional.empty(),
                Optional.of("analysis"),
                Optional.of(REJECTED_TOPIC),
                Optional.of("run-1"),
                Optional.empty(),
                Optional.of(NORMALIZED_ID),
                Optional.of("0123456789abcdef0123456789abcdef")), 10);
        assertEquals(1, filtered.size());
        assertEquals("rejected-event", filtered.getFirst().eventId());
        assertEquals("Already accepted", filtered.getFirst().explanation().orElseThrow());
    }

    /** Preserve live-cursor ordering and Processing Flow filters through the Jdbi query adapter. */
    @Test
    void shouldQueryLiveCursorAndProcessingFlowThroughJdbiPersistence() throws Exception {
        send(RAW_TOPIC, FLOW_RAW_ITEM_ID, flowRawEvent());
        awaitSqlValue(
                "SELECT count(*) FROM event_observation.observed_events WHERE event_id='"
                        + FLOW_RAW_EVENT_ID + "'",
                1L);
        send(ANALYZED_TOPIC, FLOW_NORMALIZED_ID, flowAnalyzedEvent());
        awaitSqlValue(
                "SELECT count(*) FROM event_observation.observed_events WHERE event_id='"
                        + FLOW_ANALYZED_EVENT_ID + "'",
                1L);

        EventObservationQuery query = context.getBean(EventObservationQuery.class);
        long watermark = query.currentCursor();
        EventObservationCriteria flowCriteria = new EventObservationCriteria(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(FLOW_RUN_ID),
                Optional.empty(),
                Optional.empty());
        var batch = query.pollAfter(0, flowCriteria, 10);

        assertEquals(watermark, batch.nextCursor());
        assertEquals(
                List.of(FLOW_RAW_EVENT_ID, FLOW_ANALYZED_EVENT_ID),
                batch.events().stream().map(event -> event.eventId()).toList());

        ProcessingFlowQuery flows = context.getBean(ProcessingFlowQuery.class);
        var runFlow = flows.collectionRun(FLOW_RUN_ID);
        assertEquals(FLOW_RUN_ID, runFlow.collectionRunId());
        assertEquals(2, runFlow.observedEventCount());

        var itemFlow = flows.item(FLOW_RUN_ID, FLOW_NORMALIZED_ID);
        assertEquals(Optional.of(FLOW_NORMALIZED_ID), itemFlow.itemId());
        assertTrue(itemFlow.observedEventCount() >= 1);
    }

    /** Reject direct persistence access that bypasses the application-owned transaction boundary. */
    @Test
    void shouldRequireApplicationOwnedTransactionForPersistenceAccess() {
        EventObservationRepository repository = context.getBean(EventObservationRepository.class);

        assertThrows(EventObservationPersistenceException.class, repository::currentCursor);
    }

    /** Replay one Event Observation DLQ record without republishing the shared source event. */
    @Test
    void shouldReplayDeadLetterThroughEventObservationOnly() throws Exception {
        RawItemDiscovered source = rawEvent();
        String deadLetterId = GROUP + ":" + RAW_TOPIC + ":0:73";
        DeadLetterEvent deadLetter = DeadLetterEvent.newBuilder()
                .setDeadLetterId(deadLetterId)
                .setConsumer("event-observation")
                .setConsumerGroup(GROUP)
                .setSourceTopic(RAW_TOPIC)
                .setSourcePartition(0)
                .setSourceOffset(73)
                .setSourceKey("raw-1")
                .setSourcePayload(ByteString.copyFrom(source.toByteArray()))
                .setFailureType("java.lang.IllegalStateException")
                .setFailureMessage("temporary observation failure")
                .setAttempts(3)
                .setRetryable(true)
                .build();
        var metadata = producer.send(new ProducerRecord<>(DEAD_LETTER_TOPIC, deadLetterId, deadLetter.toByteArray())).get();
        producer.flush();

        long sourceTopicEndOffsetBeforeReplay = topicEndOffset(RAW_TOPIC);

        DeadLetterRecovery recovery = context.getBean(DeadLetterRecovery.class);
        DeadLetterRecovery.Inspection inspection = recovery.inspect(metadata.partition(), metadata.offset());
        assertEquals(deadLetterId, inspection.deadLetterId());
        assertEquals(RAW_TOPIC, inspection.sourceTopic());

        recovery.replay(metadata.partition(), metadata.offset(), deadLetterId);
        awaitSqlValue("SELECT count(*) FROM event_observation.observed_events WHERE event_id='raw-event'", 1L);
        assertEquals(RAW_TOPIC, sqlString("SELECT kafka_topic FROM event_observation.observed_events WHERE event_id='raw-event'"));
        assertEquals(0L, sqlLong("SELECT kafka_partition FROM event_observation.observed_events WHERE event_id='raw-event'"));
        assertEquals(73L, sqlLong("SELECT kafka_offset FROM event_observation.observed_events WHERE event_id='raw-event'"));
        assertEquals(sourceTopicEndOffsetBeforeReplay, topicEndOffset(RAW_TOPIC));

        recovery.replay(metadata.partition(), metadata.offset(), deadLetterId);
        assertEquals(1L, sqlLong("SELECT count(*) FROM event_observation.observed_events WHERE event_id='raw-event'"));
    }

    /** Reject a real Event Observation DLQ record whose source topic is outside the observed topic allowlist. */
    @Test
    void shouldRejectDeadLetterFromUnobservedSourceTopic() throws Exception {
        RawItemDiscovered source = rawEvent();
        String foreignTopic = "signalharvester.unknown.v1";
        long sourceOffset = 74L;
        String deadLetterId = GROUP + ":" + foreignTopic + ":0:" + sourceOffset;
        DeadLetterEvent deadLetter = DeadLetterEvent.newBuilder()
                .setDeadLetterId(deadLetterId)
                .setConsumer("event-observation")
                .setConsumerGroup(GROUP)
                .setSourceTopic(foreignTopic)
                .setSourcePartition(0)
                .setSourceOffset(sourceOffset)
                .setSourceKey("raw-1")
                .setSourcePayload(ByteString.copyFrom(source.toByteArray()))
                .setFailureType("java.lang.IllegalStateException")
                .setFailureMessage("foreign source topic")
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

    /** Apply age retention before the count bound so expired high cursors do not evict valid recent history. */
    @Test
    void shouldPruneExpiredRowsWithoutConsumingTheCountRetentionBudget() throws Exception {
        executeUpdate("""
                INSERT INTO event_observation.observed_events (
                    event_id, event_type, occurred_at, observed_at, correlation_id, producer, schema_version,
                    kafka_topic, kafka_partition, kafka_offset, kafka_key, payload_type
                ) VALUES
                    ('recent-1', 'test.event', now(), now(), 'run-retention', 'test', 'v1', 'test-topic', 0, 1, 'k1', 'Test'),
                    ('recent-2', 'test.event', now(), now(), 'run-retention', 'test', 'v1', 'test-topic', 0, 2, 'k2', 'Test'),
                    ('expired-newest', 'test.event', now(), now() - interval '25 hours', 'run-retention',
                     'test', 'v1', 'test-topic', 0, 3, 'k3', 'Test')
                """);

        send(RAW_TOPIC, "raw-1", rawEvent());
        awaitSqlValue("SELECT count(*) FROM event_observation.observed_events", 2L);

        assertEquals(0L, sqlLong("SELECT count(*) FROM event_observation.observed_events WHERE event_id='expired-newest'"));
        assertEquals(0L, sqlLong("SELECT count(*) FROM event_observation.observed_events WHERE event_id='recent-1'"));
        assertEquals(1L, sqlLong("SELECT count(*) FROM event_observation.observed_events WHERE event_id='recent-2'"));
        assertEquals(1L, sqlLong("SELECT count(*) FROM event_observation.observed_events WHERE event_id='raw-event'"));
    }

    private static RawItemDiscovered rawEvent() {
        return RawItemDiscovered.newBuilder()
                .setEnvelope(envelope("raw-event", "collection.raw-item-discovered.v1", "collection"))
                .setRawItemId("raw-1")
                .setSourceId("source-1")
                .setMonitoringProfileId("profile-1")
                .setInformationCategory("JOB")
                .setExternalId("external-1")
                .setTitle("Senior Java Engineer")
                .setUrl("https://example.test/jobs/1")
                .setContent("Java Kafka")
                .setContentType("text/plain")
                .build();
    }

    private static ItemAnalyzed analyzedEvent() {
        return ItemAnalyzed.newBuilder()
                .setEnvelope(envelope("analyzed-event", "analysis.item-analyzed.v1", "analysis"))
                .setSourceEventId("raw-event")
                .setRawItemId("raw-1")
                .setNormalizedItemId(NORMALIZED_ID)
                .setSourceId("source-1")
                .setMonitoringProfileId("profile-1")
                .setInformationCategory("JOB")
                .setUrl("https://example.test/jobs/1")
                .setNormalizedContent("Java Kafka")
                .setContentType("text/plain")
                .setRelevant(true)
                .setClassification("MATCHED")
                .setScore(90)
                .setExplanation("Matched Java")
                .setAnalyzer("keyword-v1")
                .build();
    }

    private static RawItemDiscovered flowRawEvent() {
        return RawItemDiscovered.newBuilder()
                .setEnvelope(envelope(
                        FLOW_RAW_EVENT_ID,
                        "collection.raw-item-discovered.v1",
                        "collection",
                        FLOW_RUN_ID))
                .setRawItemId(FLOW_RAW_ITEM_ID)
                .setSourceId("source-jdbi-flow")
                .setMonitoringProfileId("profile-jdbi-flow")
                .setInformationCategory("JOB")
                .setTitle("Jdbi Flow")
                .setUrl("https://example.test/jobs/jdbi-flow")
                .setContent("Java Kafka")
                .setContentType("text/plain")
                .build();
    }

    private static ItemAnalyzed flowAnalyzedEvent() {
        return ItemAnalyzed.newBuilder()
                .setEnvelope(envelope(
                        FLOW_ANALYZED_EVENT_ID,
                        "analysis.item-analyzed.v1",
                        "analysis",
                        FLOW_RUN_ID))
                .setSourceEventId(FLOW_RAW_EVENT_ID)
                .setRawItemId(FLOW_RAW_ITEM_ID)
                .setNormalizedItemId(FLOW_NORMALIZED_ID)
                .setSourceId("source-jdbi-flow")
                .setMonitoringProfileId("profile-jdbi-flow")
                .setInformationCategory("JOB")
                .setUrl("https://example.test/jobs/jdbi-flow")
                .setNormalizedContent("Java Kafka")
                .setContentType("text/plain")
                .setRelevant(true)
                .setClassification("MATCHED")
                .setScore(90)
                .setExplanation("Matched Jdbi flow")
                .setAnalyzer("keyword-v1")
                .build();
    }

    private static ItemRejected rejectedEvent() {
        return ItemRejected.newBuilder()
                .setEnvelope(envelope("rejected-event", "analysis.item-rejected.v1", "analysis"))
                .setSourceEventId("raw-event-2")
                .setRawItemId("raw-2")
                .setNormalizedItemId(NORMALIZED_ID)
                .setSourceId("source-1")
                .setMonitoringProfileId("profile-1")
                .setInformationCategory("JOB")
                .setReasonCode("DUPLICATE")
                .setExplanation("Already accepted")
                .build();
    }

    private static EventEnvelope envelope(String eventId, String eventType, String producer) {
        return envelope(eventId, eventType, producer, "run-1");
    }

    private static EventEnvelope envelope(
            String eventId, String eventType, String producer, String correlationId) {
        return EventEnvelope.newBuilder()
                .setEventId(eventId)
                .setEventType(eventType)
                .setOccurredAt(Timestamp.newBuilder().setSeconds(1_700_000_000L).build())
                .setCorrelationId(correlationId)
                .setTraceparent("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01")
                .setProducer(producer)
                .setSchemaVersion("v1")
                .build();
    }

    private void send(String topic, String key, com.google.protobuf.MessageLite event) throws Exception {
        producer.send(new ProducerRecord<>(topic, key, event.toByteArray())).get();
        producer.flush();
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

    private static void executeUpdate(String sql) throws Exception {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
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
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void createTopics() throws InterruptedException, ExecutionException {
        try (Admin admin = Admin.create(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            for (String topic : List.of(RAW_TOPIC, ANALYZED_TOPIC, REJECTED_TOPIC, DEAD_LETTER_TOPIC)) {
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
            statement.execute("DROP SCHEMA IF EXISTS event_observation CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }
}
