package io.signalharvester.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.signalharvester.collection.run.CollectionRunRequest;
import io.signalharvester.collection.run.CollectionRunResult;
import io.signalharvester.collection.run.CollectionRunner;
import io.signalharvester.configuration.api.ConfiguredMonitoringProfile;
import io.signalharvester.configuration.api.SourceType;
import io.signalharvester.configuration.application.MonitoringProfileConfigurationCommand;
import io.signalharvester.configuration.application.MonitoringProfileConfigurationOperations;
import io.signalharvester.configuration.application.SourceConfigurationCommand;
import io.signalharvester.configuration.application.SourceConfigurationOperations;
import io.signalharvester.events.analysis.v1.ItemAnalyzed;
import io.signalharvester.events.analysis.v1.ItemRejected;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies the cross-module flow from {@link io.signalharvester.collection.run.CollectionRunService} through
 * Kafka into {@link io.signalharvester.analysis.application.RawItemProcessingService}, including rediscovery.
 *
 * <p>Related specifications: {@code backend-collection-run-orchestration},
 * {@code backend-analysis-normalization-deduplication}.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class CollectionAnalysisIntegrationTest {

    private static final String RAW_TOPIC = "signalharvester.collection.raw-item-discovered.v1.analysis-test";
    private static final String ANALYZED_TOPIC = "signalharvester.analysis.item-analyzed.v1.test";
    private static final String REJECTED_TOPIC = "signalharvester.analysis.item-rejected.v1.test";
    private static final String ANALYSIS_GROUP = "signalharvester-analysis-integration-test";
    private static String profileId;
    private static final String NORMALIZED_CONTENT = "Java backend Kafka";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("signalharvester")
            .withUsername("signalharvester")
            .withPassword("signalharvester");

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    private ApplicationContext context;
    private HttpServer sourceServer;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        createTopics();
        sourceServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sourceServer.createContext("/jobs", this::respondWithEquivalentContent);
        sourceServer.start();

        context = ApplicationContext.run(Map.<String, Object>ofEntries(
                Map.entry("micronaut.executors.blocking.virtual", true),
                Map.entry("datasources.default.url", POSTGRES.getJdbcUrl()),
                Map.entry("datasources.default.username", POSTGRES.getUsername()),
                Map.entry("datasources.default.password", POSTGRES.getPassword()),
                Map.entry("datasources.default.driver-class-name", "org.postgresql.Driver"),
                Map.entry("flyway.datasources.default.enabled", true),
                Map.entry("flyway.datasources.default.locations[0]", "classpath:db/migration/configuration"),
                Map.entry("flyway.datasources.default.locations[1]", "classpath:db/migration/analysis"),
                Map.entry("flyway.datasources.default.locations[2]", "classpath:db/migration/collection"),
                Map.entry("kafka.enabled", true),
                Map.entry("kafka.bootstrap.servers", KAFKA.getBootstrapServers()),
                Map.entry("kafka.producers.collection-raw-items.key.serializer",
                        "org.apache.kafka.common.serialization.StringSerializer"),
                Map.entry("kafka.producers.collection-raw-items.value.serializer",
                        "org.apache.kafka.common.serialization.ByteArraySerializer"),
                Map.entry("kafka.producers.collection-raw-items.acks", "all"),
                Map.entry("kafka.producers.collection-raw-items.enable.idempotence", true),
                Map.entry("kafka.producers.analysis-events.key.serializer",
                        "org.apache.kafka.common.serialization.StringSerializer"),
                Map.entry("kafka.producers.analysis-events.value.serializer",
                        "org.apache.kafka.common.serialization.ByteArraySerializer"),
                Map.entry("kafka.producers.analysis-events.acks", "all"),
                Map.entry("kafka.producers.analysis-events.enable.idempotence", true),
                Map.entry("kafka.producers.analysis-dead-letter.key.serializer",
                        "org.apache.kafka.common.serialization.StringSerializer"),
                Map.entry("kafka.producers.analysis-dead-letter.value.serializer",
                        "org.apache.kafka.common.serialization.ByteArraySerializer"),
                Map.entry("kafka.producers.analysis-dead-letter.acks", "all"),
                Map.entry("kafka.producers.analysis-dead-letter.enable.idempotence", true),
                Map.entry("kafka.consumers." + ANALYSIS_GROUP + ".key.deserializer",
                        "org.apache.kafka.common.serialization.StringDeserializer"),
                Map.entry("kafka.consumers." + ANALYSIS_GROUP + ".value.deserializer",
                        "org.apache.kafka.common.serialization.ByteArrayDeserializer"),
                Map.entry("signalharvester.kafka.raw-item-discovered-topic", RAW_TOPIC),
                Map.entry("signalharvester.kafka.item-analyzed-topic", ANALYZED_TOPIC),
                Map.entry("signalharvester.kafka.item-rejected-topic", REJECTED_TOPIC),
                Map.entry("signalharvester.analysis.consumer-group", ANALYSIS_GROUP),
                Map.entry("signalharvester.analysis.outbox.poll-interval", "50ms"),
                Map.entry("signalharvester.results.enabled", false),
                Map.entry(
                        "signalharvester.analysis.keyword-rules.keywords",
                        List.of("java", "kafka", "postgresql")),
                Map.entry("signalharvester.analysis.keyword-rules.minimum-matches", 1),
                Map.entry("signalharvester.collection.max-concurrency", 2),
                Map.entry("signalharvester.collection.scheduler.enabled", false)));
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        if (sourceServer != null) {
            sourceServer.stop(0);
        }
        requests.set(0);
    }

    /**
     * Normalize analyze and reject equivalent rediscovery as duplicate.
     */
    @Test
    void shouldNormalizeAnalyzeAndRejectEquivalentRediscoveryAsDuplicate() throws Exception {
        SourceConfigurationOperations configuration = context.getBean(SourceConfigurationOperations.class);
        URI sourceUri = URI.create("http://127.0.0.1:" + sourceServer.getAddress().getPort() + "/jobs");
        var source = configuration.create(new SourceConfigurationCommand(
                "Jobs",
                SourceType.REST,
                sourceUri,
                true,
                Map.of()));
        MonitoringProfileConfigurationOperations profiles = context.getBean(MonitoringProfileConfigurationOperations.class);
        ConfiguredMonitoringProfile profile = profiles.create(new MonitoringProfileConfigurationCommand(
                "Analysis profile",
                "JOB",
                true,
                5,
                List.of(source.id()),
                Map.of()));
        profileId = profile.id().value().toString();

        CollectionRunner collection = context.getBean(CollectionRunner.class);
        CollectionRunResult firstRun = collection.run(
                new CollectionRunRequest(profile.id(), Optional.empty()));
        CollectionRunResult secondRun = collection.run(
                new CollectionRunRequest(profile.id(), Optional.empty()));

        assertNotEquals(firstRun.collectionRunId(), secondRun.collectionRunId());
        assertNotEquals(
                firstRun.sources().getFirst().rawItemId().orElseThrow(),
                secondRun.sources().getFirst().rawItemId().orElseThrow());

        List<ConsumerRecord<String, byte[]>> output = consumeOutputs(2);
        ConsumerRecord<String, byte[]> analyzedRecord = output.stream()
                .filter(record -> ANALYZED_TOPIC.equals(record.topic()))
                .findFirst()
                .orElseThrow();
        ConsumerRecord<String, byte[]> rejectedRecord = output.stream()
                .filter(record -> REJECTED_TOPIC.equals(record.topic()))
                .findFirst()
                .orElseThrow();

        ItemAnalyzed analyzed = ItemAnalyzed.parseFrom(analyzedRecord.value());
        ItemRejected rejected = ItemRejected.parseFrom(rejectedRecord.value());

        assertEquals(analyzed.getNormalizedItemId(), analyzedRecord.key());
        assertEquals(analyzed.getNormalizedItemId(), rejectedRecord.key());
        assertEquals(analyzed.getNormalizedItemId(), rejected.getNormalizedItemId());
        assertEquals(NORMALIZED_CONTENT, analyzed.getNormalizedContent());
        assertTrue(analyzed.getRelevant());
        assertEquals("MATCHED_KEYWORDS", analyzed.getClassification());
        assertEquals(67, analyzed.getScore());
        assertEquals(List.of("java", "kafka"), analyzed.getTagsList());
        assertEquals("keyword-v1", analyzed.getAnalyzer());
        assertEquals("DUPLICATE", rejected.getReasonCode());
        assertEquals(firstRun.collectionRunId(), analyzed.getEnvelope().getCorrelationId());
        assertEquals(secondRun.collectionRunId(), rejected.getEnvelope().getCorrelationId());

        assertDeduplicationState(analyzed.getNormalizedItemId());
    }

    private void respondWithEquivalentContent(HttpExchange exchange) throws IOException {
        String body = requests.getAndIncrement() == 0
                ? "  Java   backend\nKafka  "
                : NORMALIZED_CONTENT;
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(200, payload.length);
        try (var response = exchange.getResponseBody()) {
            response.write(payload);
        }
    }

    private List<ConsumerRecord<String, byte[]>> consumeOutputs(int expected) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "analysis-output-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());

        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(ANALYZED_TOPIC, REJECTED_TOPIC));
            Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
            while (records.size() < expected && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(250)).forEach(records::add);
            }
        }
        if (records.size() != expected) {
            throw new AssertionError("Expected " + expected + " analysis records but received " + records.size());
        }
        return List.copyOf(records);
    }

    private static void assertDeduplicationState(String normalizedItemId) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                        SELECT monitoring_profile_id, normalized_item_id, discovery_count
                          FROM analysis.normalized_item_claims
                        """)) {
            assertTrue(resultSet.next());
            assertEquals(profileId, resultSet.getString("monitoring_profile_id"));
            assertEquals(normalizedItemId, resultSet.getString("normalized_item_id"));
            assertEquals(2, resultSet.getLong("discovery_count"));
            assertTrue(!resultSet.next());
        }
    }

    private static void createTopics() throws InterruptedException, ExecutionException {
        try (Admin admin = Admin.create(Map.<String, Object>of(
                "bootstrap.servers", KAFKA.getBootstrapServers()))) {
            try {
                admin.createTopics(List.of(
                                new NewTopic(RAW_TOPIC, 1, (short) 1),
                                new NewTopic(ANALYZED_TOPIC, 1, (short) 1),
                                new NewTopic(REJECTED_TOPIC, 1, (short) 1)))
                        .all()
                        .get();
            } catch (ExecutionException failure) {
                if (!(failure.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
                    throw failure;
                }
            }
        }
    }

    private static void resetDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS configuration CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS analysis CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS collection CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS results CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS event_observation CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }
}
