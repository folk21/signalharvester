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
import io.signalharvester.collection.run.CollectionRunStatus;
import io.signalharvester.collection.run.CollectionSourceStatus;
import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceType;
import io.signalharvester.configuration.application.SourceConfigurationCommand;
import io.signalharvester.configuration.application.SourceConfigurationOperations;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
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
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class CollectionRunIntegrationTest {

    private static final String TOPIC = "signalharvester.collection.raw-item-discovered.v1.run-test";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("signalharvester")
            .withUsername("signalharvester")
            .withPassword("signalharvester");

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    private ApplicationContext context;
    private HttpServer sourceServer;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        createTopic();
        sourceServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sourceServer.createContext("/a-success", exchange -> respond(exchange, 200, "alpha payload"));
        sourceServer.createContext("/b-failure", exchange -> respond(exchange, 503, "temporary failure"));
        sourceServer.createContext("/c-success", exchange -> respond(exchange, 200, "charlie payload"));
        sourceServer.start();

        context = ApplicationContext.run(Map.<String, Object>ofEntries(
                Map.entry("micronaut.executors.blocking.virtual", true),
                Map.entry("datasources.default.url", POSTGRES.getJdbcUrl()),
                Map.entry("datasources.default.username", POSTGRES.getUsername()),
                Map.entry("datasources.default.password", POSTGRES.getPassword()),
                Map.entry("datasources.default.driver-class-name", "org.postgresql.Driver"),
                Map.entry("flyway.datasources.default.enabled", true),
                Map.entry("flyway.datasources.default.locations[0]", "classpath:db/migration/configuration"),
                Map.entry("kafka.enabled", true),
                Map.entry("kafka.bootstrap.servers", KAFKA.getBootstrapServers()),
                Map.entry("kafka.producers.collection-raw-items.key.serializer",
                        "org.apache.kafka.common.serialization.StringSerializer"),
                Map.entry("kafka.producers.collection-raw-items.value.serializer",
                        "org.apache.kafka.common.serialization.ByteArraySerializer"),
                Map.entry("kafka.producers.collection-raw-items.acks", "all"),
                Map.entry("kafka.producers.collection-raw-items.enable.idempotence", true),
                Map.entry("signalharvester.kafka.raw-item-discovered-topic", TOPIC),
                Map.entry("signalharvester.analysis.enabled", false),
                Map.entry("signalharvester.collection.max-concurrency", 2)));
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        if (sourceServer != null) {
            sourceServer.stop(0);
        }
    }

    @Test
    void shouldCollectEnabledSourcesBestEffortAndPublishSuccessfulPayloads() throws Exception {
        SourceConfigurationOperations configuration = context.getBean(SourceConfigurationOperations.class);
        ConfiguredSource alpha = configuration.create(command("A success", "/a-success", true));
        ConfiguredSource failing = configuration.create(command("B failure", "/b-failure", true));
        ConfiguredSource charlie = configuration.create(command("C success", "/c-success", true));
        configuration.create(command("D disabled", "/a-success", false));

        CollectionRunResult result = context.getBean(CollectionRunner.class).run(
                new CollectionRunRequest("profile-integration", "JOB", Optional.empty()));

        assertEquals(CollectionRunStatus.PARTIALLY_SUCCEEDED, result.status());
        assertEquals(2, result.publishedCount());
        assertEquals(1, result.failedCount());
        assertEquals(List.of(alpha.id(), failing.id(), charlie.id()), result.sources().stream()
                .map(source -> source.sourceId())
                .toList());
        assertEquals(List.of(
                CollectionSourceStatus.PUBLISHED,
                CollectionSourceStatus.FETCH_FAILED,
                CollectionSourceStatus.PUBLISHED), result.sources().stream().map(source -> source.status()).toList());

        List<ConsumerRecord<String, byte[]>> records = consume(2);
        Set<String> publishedSourceIds = new HashSet<>();
        Set<String> rawItemIds = new HashSet<>();
        Set<String> eventIds = new HashSet<>();
        for (ConsumerRecord<String, byte[]> record : records) {
            RawItemDiscovered event = RawItemDiscovered.parseFrom(record.value());
            assertEquals(TOPIC, record.topic());
            assertEquals(record.key(), event.getRawItemId());
            assertEquals(result.collectionRunId(), event.getEnvelope().getCorrelationId());
            assertEquals("profile-integration", event.getMonitoringProfileId());
            assertEquals("JOB", event.getInformationCategory());
            publishedSourceIds.add(event.getSourceId());
            rawItemIds.add(event.getRawItemId());
            eventIds.add(event.getEnvelope().getEventId());
        }

        assertEquals(Set.of(alpha.id().value().toString(), charlie.id().value().toString()), publishedSourceIds);
        assertEquals(2, rawItemIds.size());
        assertEquals(2, eventIds.size());
        assertTrue(eventIds.stream().noneMatch(String::isBlank));
        assertNotEquals(result.collectionRunId(), eventIds.iterator().next());
    }

    private SourceConfigurationCommand command(String name, String path, boolean enabled) {
        URI location = URI.create("http://127.0.0.1:" + sourceServer.getAddress().getPort() + path);
        return new SourceConfigurationCommand(name, SourceType.REST, location, enabled, Map.of());
    }

    private List<ConsumerRecord<String, byte[]>> consume(int expected) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "collection-run-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());

        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(TOPIC));
            Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
            while (records.size() < expected && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(250)).forEach(records::add);
            }
        }
        if (records.size() != expected) {
            throw new AssertionError("Expected " + expected + " Kafka records but received " + records.size());
        }
        return List.copyOf(records);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, payload.length);
        try (var response = exchange.getResponseBody()) {
            response.write(payload);
        }
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

    private static void resetDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS configuration CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }
}
