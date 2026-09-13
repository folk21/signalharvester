package io.signalharvester.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Black-box smoke coverage for the public backend surface implemented by
 * {@link io.signalharvester.configuration.http.SourceController},
 * {@link io.signalharvester.collection.http.CollectionRunController}, and
 * {@link io.signalharvester.analysis.http.AnalysisItemInspectionController} over real PostgreSQL and Kafka.
 *
 * <p>The test drives source creation, repeated collection, durable run history, and analysis inspection only
 * through HTTP. Until Results persistence exists, analysis inspection is the terminal observable boundary.</p>
 *
 * <p>Related specifications: {@code backend-configuration-persistence-rest},
 * {@code backend-collection-run-orchestration}, {@code backend-analysis-normalization-deduplication},
 * {@code backend-operational-admin-api}.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class HttpPipelineSmokeIntegrationTest {

    private static final String RAW_TOPIC = "signalharvester.collection.raw-item-discovered.v1.http-smoke";
    private static final String ANALYZED_TOPIC = "signalharvester.analysis.item-analyzed.v1.http-smoke";
    private static final String REJECTED_TOPIC = "signalharvester.analysis.item-rejected.v1.http-smoke";
    private static final String ANALYSIS_GROUP = "signalharvester-analysis-http-smoke";
    private static final String PROFILE_ID = "profile-http-smoke";
    private static final Pattern SOURCE_ID = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern RUN_ID = Pattern.compile("\\\"collectionRunId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern RAW_ITEM_ID = Pattern.compile("\\\"rawItemId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern EVENT_ID = Pattern.compile("\\\"eventId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern NORMALIZED_ITEM_ID = Pattern.compile(
            "\\\"normalizedItemId\\\"\\s*:\\s*\\\"([0-9a-f]{64})\\\"");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("signalharvester")
            .withUsername("signalharvester")
            .withPassword("signalharvester");

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    private final AtomicInteger sourceRequests = new AtomicInteger();
    private HttpServer sourceServer;
    private EmbeddedServer backend;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        createTopics();

        sourceServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sourceServer.createContext("/jobs", this::respondWithSourceContent);
        sourceServer.start();

        backend = ApplicationContext.run(EmbeddedServer.class, backendProperties(), "test");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        if (backend != null) {
            backend.close();
        }
        if (sourceServer != null) {
            sourceServer.stop(0);
        }
        sourceRequests.set(0);
    }

    /**
     * Drive configured source through collection Kafka and analysis using only HTTP application APIs.
     */
    @Test
    void shouldDriveConfiguredSourceThroughCollectionKafkaAndAnalysisUsingOnlyHttpApplicationApis() throws Exception {
        String sourceUrl = "http://127.0.0.1:" + sourceServer.getAddress().getPort() + "/jobs";
        HttpResponse<String> created = send("POST", "/api/v1/sources", """
                {
                  "name": "HTTP smoke source",
                  "type": "REST",
                  "location": "%s",
                  "enabled": true,
                  "settings": {}
                }
                """.formatted(sourceUrl));
        assertEquals(201, created.statusCode());
        String sourceId = extract(SOURCE_ID, created.body(), "source id");
        assertEquals(36, sourceId.length());

        HttpResponse<String> listedSources = send("GET", "/api/v1/sources", null);
        assertEquals(200, listedSources.statusCode());
        assertTrue(listedSources.body().contains(sourceId));
        assertTrue(listedSources.body().contains("HTTP smoke source"));

        HttpResponse<String> firstRun = startRun();
        HttpResponse<String> secondRun = startRun();
        assertEquals(201, firstRun.statusCode());
        assertEquals(201, secondRun.statusCode());

        String firstRunId = extract(RUN_ID, firstRun.body(), "first collection run id");
        String secondRunId = extract(RUN_ID, secondRun.body(), "second collection run id");
        String firstRawItemId = extract(RAW_ITEM_ID, firstRun.body(), "first raw item id");
        String secondRawItemId = extract(RAW_ITEM_ID, secondRun.body(), "second raw item id");
        String firstEventId = extract(EVENT_ID, firstRun.body(), "first event id");
        String secondEventId = extract(EVENT_ID, secondRun.body(), "second event id");

        assertNotEquals(firstRunId, secondRunId);
        assertEquals(firstRawItemId, secondRawItemId);
        assertNotEquals(firstEventId, secondEventId);
        assertTrue(firstRun.body().contains("\"status\":\"SUCCEEDED\""));
        assertTrue(secondRun.body().contains("\"status\":\"SUCCEEDED\""));
        assertEquals(2, sourceRequests.get());

        HttpResponse<String> durableRun = send("GET", "/api/v1/admin/collection-runs/" + secondRunId, null);
        assertEquals(200, durableRun.statusCode());
        assertTrue(durableRun.body().contains(secondRunId));
        assertTrue(durableRun.body().contains(secondRawItemId));
        assertTrue(durableRun.body().contains(sourceId));

        String analysisBody = awaitAnalysisState(sourceId, firstRawItemId, 2);
        String normalizedItemId = extract(NORMALIZED_ITEM_ID, analysisBody, "normalized item id");
        assertTrue(analysisBody.contains("\"monitoringProfileId\":\"" + PROFILE_ID + "\""));
        assertTrue(analysisBody.contains("\"sourceId\":\"" + sourceId + "\""));
        assertTrue(analysisBody.contains("\"firstRawItemId\":\"" + firstRawItemId + "\""));
        assertTrue(analysisBody.contains("\"lastRawItemId\":\"" + secondRawItemId + "\""));
        assertTrue(analysisBody.contains("\"discoveryCount\":2"));

        HttpResponse<String> inspected = send(
                "GET",
                "/api/v1/admin/analysis/items/" + normalizedItemId + "?monitoringProfileId=" + PROFILE_ID,
                null);
        assertEquals(200, inspected.statusCode());
        assertTrue(inspected.body().contains(normalizedItemId));
        assertTrue(inspected.body().contains("\"discoveryCount\":2"));
    }

    private HttpResponse<String> startRun() throws Exception {
        return send("POST", "/api/v1/admin/collection-runs", """
                {
                  "monitoringProfileId": "%s",
                  "informationCategory": "JOB"
                }
                """.formatted(PROFILE_ID));
    }

    private String awaitAnalysisState(String sourceId, String rawItemId, long discoveryCount) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        String lastBody = "";
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = send(
                    "GET",
                    "/api/v1/admin/analysis/items?limit=10&monitoringProfileId=" + PROFILE_ID + "&sourceId=" + sourceId,
                    null);
            assertEquals(200, response.statusCode());
            lastBody = response.body();
            if (lastBody.contains("\"firstRawItemId\":\"" + rawItemId + "\"")
                    && lastBody.contains("\"discoveryCount\":" + discoveryCount)) {
                return lastBody;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Analysis state did not reach discoveryCount=" + discoveryCount + ": " + lastBody);
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(backend.getURI().resolve(path))
                .timeout(Duration.ofSeconds(20));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> backendProperties() {
        return Map.<String, Object>ofEntries(
                Map.entry("micronaut.server.port", -1),
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
                Map.entry("kafka.consumers." + ANALYSIS_GROUP + ".key.deserializer",
                        "org.apache.kafka.common.serialization.StringDeserializer"),
                Map.entry("kafka.consumers." + ANALYSIS_GROUP + ".value.deserializer",
                        "org.apache.kafka.common.serialization.ByteArrayDeserializer"),
                Map.entry("signalharvester.kafka.raw-item-discovered-topic", RAW_TOPIC),
                Map.entry("signalharvester.kafka.item-analyzed-topic", ANALYZED_TOPIC),
                Map.entry("signalharvester.kafka.item-rejected-topic", REJECTED_TOPIC),
                Map.entry("signalharvester.analysis.enabled", true),
                Map.entry("signalharvester.analysis.consumer-group", ANALYSIS_GROUP),
                Map.entry("signalharvester.analysis.keyword-rules.keywords", List.of("java", "kafka", "postgresql")),
                Map.entry("signalharvester.analysis.keyword-rules.minimum-matches", 1),
                Map.entry("signalharvester.collection.max-concurrency", 2));
    }

    private void respondWithSourceContent(HttpExchange exchange) throws IOException {
        sourceRequests.incrementAndGet();
        byte[] payload = "Java backend Kafka".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(200, payload.length);
        try (var response = exchange.getResponseBody()) {
            response.write(payload);
        }
    }

    private static String extract(Pattern pattern, String body, String description) {
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            throw new AssertionError("Missing " + description + " in response: " + body);
        }
        return matcher.group(1);
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
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS configuration CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS analysis CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS collection CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }
}
