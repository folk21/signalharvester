package io.signalharvester.eventobservation.event.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Timestamp;
import io.micronaut.context.ApplicationContext;
import io.signalharvester.eventobservation.application.EventObservationCriteria;
import io.signalharvester.eventobservation.application.EventObservationQuery;
import io.signalharvester.events.analysis.v1.ItemAnalyzed;
import io.signalharvester.events.analysis.v1.ItemRejected;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import io.signalharvester.events.common.v1.EventEnvelope;
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
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Verifies Kafka decoding, idempotent persistence, and bounded retention for {@link EventObservationKafkaListener}. */
@Testcontainers(disabledWithoutDocker = true)
class EventObservationKafkaPostgresIntegrationTest {

    private static final String RAW_TOPIC = "signalharvester.collection.raw-item-discovered.v1.observation-it";
    private static final String ANALYZED_TOPIC = "signalharvester.analysis.item-analyzed.v1.observation-it";
    private static final String REJECTED_TOPIC = "signalharvester.analysis.item-rejected.v1.observation-it";
    private static final String GROUP = "signalharvester-event-observation-it";
    private static final String NORMALIZED_ID = "c".repeat(64);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("signalharvester")
            .withUsername("signalharvester")
            .withPassword("signalharvester");

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

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
                Map.entry("signalharvester.event-observation.kafka-reliability.dead-letter-topic",
                        "signalharvester.event-observation.dead-letter.v1.it"),
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
        return EventEnvelope.newBuilder()
                .setEventId(eventId)
                .setEventType(eventType)
                .setOccurredAt(Timestamp.newBuilder().setSeconds(1_700_000_000L).build())
                .setCorrelationId("run-1")
                .setTraceparent("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01")
                .setProducer(producer)
                .setSchemaVersion("v1")
                .build();
    }

    private void send(String topic, String key, com.google.protobuf.MessageLite event) throws Exception {
        producer.send(new ProducerRecord<>(topic, key, event.toByteArray())).get();
        producer.flush();
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
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            long actual = sqlLong(sql);
            if (actual == expected) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for SQL value " + expected + ": " + sql);
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
            for (String topic : List.of(RAW_TOPIC, ANALYZED_TOPIC, REJECTED_TOPIC)) {
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
