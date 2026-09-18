package io.signalharvester.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import io.signalharvester.testing.Await;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
 * {@link io.signalharvester.collection.http.SourceTestController},
 * {@link io.signalharvester.collection.http.CollectionRunController}, and
 * {@link io.signalharvester.analysis.http.AnalysisItemInspectionController}, and
 * {@link io.signalharvester.results.http.ResultLiveController}, and
 * {@link io.signalharvester.eventobservation.http.EventObservationController}, and
 * {@link io.signalharvester.eventobservation.http.ProcessingFlowController} over real PostgreSQL and Kafka.
 *
 * <p>The test drives source creation, diagnostic source testing, repeated collection, durable run history,
 * analysis inspection, persisted Results browsing, live Results SSE, technical event history, and processing-flow
 * reconstruction only through HTTP.</p>
 *
 * <p>Related specifications: {@code backend-configuration-persistence-rest},
 * {@code backend-collection-run-orchestration}, {@code backend-analysis-normalization-deduplication},
 * {@code backend-operational-admin-api}, {@code backend-source-test-generic-extraction},
 * {@code backend-results-sse-live-delivery}, {@code backend-event-observation}, and
 * {@code backend-processing-flow-reconstruction}.</p>
 *
 * <p>Features: {@code TESTING.DETERMINISTIC_LOCAL}, {@code EVENTING.PIPELINE}, {@code RESULTS.LIVE}, {@code DIAGNOSTICS.EVENT_OBSERVATION}.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class HttpPipelineSmokeIntegrationTest {

    private static final String RAW_TOPIC = "signalharvester.collection.raw-item-discovered.v1.http-smoke";
    private static final String ANALYZED_TOPIC = "signalharvester.analysis.item-analyzed.v1.http-smoke";
    private static final String REJECTED_TOPIC = "signalharvester.analysis.item-rejected.v1.http-smoke";
    private static final String ANALYSIS_GROUP = "signalharvester-analysis-http-smoke";
    private static final String RESULTS_GROUP = "signalharvester-results-http-smoke";
    private static final String OBSERVATION_GROUP = "signalharvester-event-observation-http-smoke";
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
        sourceServer.createContext("/json", this::respondWithJsonSourceContent);
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

        HttpResponse<String> createdProfile = send("POST", "/api/v1/monitoring-profiles", """
                {
                  "name": "HTTP smoke profile",
                  "informationCategory": "JOB",
                  "enabled": true,
                  "collectionIntervalMinutes": 5,
                  "sourceIds": ["%s"],
                  "criteria": {}
                }
                """.formatted(sourceId));
        assertEquals(201, createdProfile.statusCode());
        String profileId = extract(SOURCE_ID, createdProfile.body(), "monitoring profile id");
        assertEquals(36, profileId.length());

        HttpResponse<InputStream> liveResponse = openResultStream(profileId);
        assertEquals(200, liveResponse.statusCode());
        BufferedReader liveReader = new BufferedReader(new InputStreamReader(liveResponse.body(), StandardCharsets.UTF_8));
        String readyEvent = readSseUntil(liveReader, "ready", Duration.ofSeconds(5));
        assertTrue(readyEvent.contains("event:"));

        HttpResponse<String> firstRun = startRun(profileId);
        HttpResponse<String> secondRun = startRun(profileId);
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

        String analysisBody = awaitAnalysisState(profileId, sourceId, firstRawItemId, 2);
        String normalizedItemId = extract(NORMALIZED_ITEM_ID, analysisBody, "normalized item id");
        assertTrue(analysisBody.contains("\"monitoringProfileId\":\"" + profileId + "\""));
        assertTrue(analysisBody.contains("\"sourceId\":\"" + sourceId + "\""));
        assertTrue(analysisBody.contains("\"firstRawItemId\":\"" + firstRawItemId + "\""));
        assertTrue(analysisBody.contains("\"lastRawItemId\":\"" + secondRawItemId + "\""));
        assertTrue(analysisBody.contains("\"discoveryCount\":2"));

        HttpResponse<String> inspected = send(
                "GET",
                "/api/v1/admin/analysis/items/" + normalizedItemId + "?monitoringProfileId=" + profileId,
                null);
        assertEquals(200, inspected.statusCode());
        assertTrue(inspected.body().contains(normalizedItemId));
        assertTrue(inspected.body().contains("\"discoveryCount\":2"));

        String liveEvent;
        try (InputStream ignored = liveResponse.body()) {
            liveEvent = readSseUntil(liveReader, normalizedItemId, Duration.ofSeconds(20));
        }
        assertTrue(liveEvent.contains("event:result") || liveEvent.contains("event: result"));
        assertTrue(liveEvent.contains("\"monitoringProfileId\":\"" + profileId + "\""));
        assertTrue(liveEvent.contains("\"sourceId\":\"" + sourceId + "\""));

        String resultsBody = awaitResultsFeed(profileId, normalizedItemId);
        assertTrue(resultsBody.contains("\"normalizedItemId\":\"" + normalizedItemId + "\""));

        String eventHistory = awaitEventHistory(normalizedItemId);
        assertTrue(eventHistory.contains("\"eventType\":\"collection.raw-item-discovered.v1\""));
        assertTrue(eventHistory.contains("\"eventType\":\"analysis.item-analyzed.v1\""));
        assertTrue(eventHistory.contains("\"topic\":\"" + ANALYZED_TOPIC + "\""));

        String duplicateFlow = awaitProcessingFlow(secondRunId, normalizedItemId);
        assertTrue(duplicateFlow.contains("\"scope\":\"ITEM\""));
        assertTrue(duplicateFlow.contains("\"collectionRunId\":\"" + secondRunId + "\""));
        assertTrue(duplicateFlow.contains("\"branchId\":\"" + secondEventId + "\""));
        assertTrue(duplicateFlow.contains("\"stage\":\"DEDUPLICATION\""));
        assertTrue(duplicateFlow.contains("\"status\":\"REJECTED\""));
        assertTrue(duplicateFlow.contains("\"stage\":\"RESULTS_PERSISTENCE\""));
        assertTrue(duplicateFlow.contains("\"evidence\":\"NOT_OBSERVED\""));
        assertFalse(duplicateFlow.contains("\"branchId\":\"" + firstEventId + "\""));
    }

    /**
     * Test persisted JSON extraction through the public source-test API without creating collection history.
     */
    @Test
    void shouldTestConfiguredJsonSourceWithoutPublishingCollectionRun() throws Exception {
        String sourceUrl = "http://127.0.0.1:" + sourceServer.getAddress().getPort() + "/json";
        HttpResponse<String> created = send("POST", "/api/v1/sources", """
                {
                  "name": "JSON source test",
                  "type": "REST",
                  "location": "%s",
                  "enabled": false,
                  "settings": {
                    "json.itemsPointer": "/items",
                    "json.externalIdPointer": "/id",
                    "json.titlePointer": "/title",
                    "json.urlPointer": "/url",
                    "json.contentPointer": "/content"
                  }
                }
                """.formatted(sourceUrl));
        assertEquals(201, created.statusCode());
        String sourceId = extract(SOURCE_ID, created.body(), "source id");

        HttpResponse<String> tested = send("POST", "/api/v1/sources/" + sourceId + "/test", null);

        assertEquals(200, tested.statusCode());
        assertTrue(tested.body().contains("\"status\":\"SUCCEEDED\""));
        assertTrue(tested.body().contains("\"candidateItemCount\":2"));
        assertTrue(tested.body().contains("\"externalId\":\"job-1\""));
        assertTrue(tested.body().contains("\"title\":\"Java Engineer\""));
        assertTrue(tested.body().contains("\"url\":\"" + sourceUrl.replace("/json", "/jobs/1") + "\""));
        assertEquals(1, sourceRequests.get());

        HttpResponse<String> runs = send("GET", "/api/v1/admin/collection-runs", null);
        assertEquals(200, runs.statusCode());
        assertEquals("[]", runs.body());
    }

    private HttpResponse<String> startRun(String profileId) throws Exception {
        return send("POST", "/api/v1/admin/collection-runs", """
                {
                  "monitoringProfileId": "%s"
                }
                """.formatted(profileId));
    }

    private String awaitAnalysisState(
            String profileId, String sourceId, String rawItemId, long discoveryCount) throws Exception {
        String expectedRawItem = "\"firstRawItemId\":\"" + rawItemId + "\"";
        String expectedCount = "\"discoveryCount\":" + discoveryCount;
        return Await.until(
                "analysis discoveryCount=" + discoveryCount,
                Duration.ofSeconds(20),
                Duration.ofMillis(100),
                () -> {
                    HttpResponse<String> response = send(
                            "GET",
                            "/api/v1/admin/analysis/items?limit=10&monitoringProfileId=" + profileId + "&sourceId=" + sourceId,
                            null);
                    assertEquals(200, response.statusCode());
                    return response.body();
                },
                body -> body.contains(expectedRawItem) && body.contains(expectedCount));
    }

    private String awaitEventHistory(String normalizedItemId) throws Exception {
        String expectedItem = "\"normalizedItemId\":\"" + normalizedItemId + "\"";
        return Await.until(
                "event observation for normalized item " + normalizedItemId,
                Duration.ofSeconds(20),
                Duration.ofMillis(100),
                () -> {
                    HttpResponse<String> response = send("GET", "/api/v1/events?limit=20", null);
                    assertEquals(200, response.statusCode());
                    return response.body();
                },
                body -> body.contains(expectedItem));
    }

    private String awaitProcessingFlow(String collectionRunId, String itemId) throws Exception {
        HttpResponse<String> response = Await.until(
                "terminal processing flow for run=" + collectionRunId + ", item=" + itemId,
                Duration.ofSeconds(20),
                Duration.ofMillis(100),
                () -> {
                    HttpResponse<String> current = send(
                            "GET",
                            "/api/v1/flows/collection-runs/" + collectionRunId + "/items/" + itemId,
                            null);
                    if (current.statusCode() != 200 && current.statusCode() != 404) {
                        throw new AssertionError(
                                "Unexpected processing-flow status " + current.statusCode() + ": " + current.body());
                    }
                    return current;
                },
                current -> current.statusCode() == 200
                        && current.body().contains("\"state\":\"TERMINAL_EVENT_REACHED\""));
        return response.body();
    }

    private HttpResponse<InputStream> openResultStream(String profileId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        backend.getURI().resolve("/api/v1/results/stream?monitoringProfileId=" + profileId))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private String awaitResultsFeed(String profileId, String normalizedItemId) throws Exception {
        String expectedItem = "\"normalizedItemId\":\"" + normalizedItemId + "\"";
        return Await.until(
                "Results feed item " + normalizedItemId,
                Duration.ofSeconds(20),
                Duration.ofMillis(100),
                () -> {
                    HttpResponse<String> response = send(
                            "GET", "/api/v1/results?limit=10&monitoringProfileId=" + profileId, null);
                    assertEquals(200, response.statusCode());
                    return response.body();
                },
                body -> body.contains(expectedItem));
    }

    private static String readSseUntil(BufferedReader reader, String target, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        StringBuilder received = new StringBuilder();
        while (Instant.now().isBefore(deadline)) {
            if (!reader.ready()) {
                Thread.sleep(10);
                continue;
            }
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            received.append(line).append('\n');
            if (line.contains(target)) {
                return received.toString();
            }
        }
        throw new AssertionError("Timed out waiting for SSE content " + target + ": " + received);
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
                Map.entry("kafka.producers.analysis-dead-letter.key.serializer",
                        "org.apache.kafka.common.serialization.StringSerializer"),
                Map.entry("kafka.producers.analysis-dead-letter.value.serializer",
                        "org.apache.kafka.common.serialization.ByteArraySerializer"),
                Map.entry("kafka.producers.analysis-dead-letter.acks", "all"),
                Map.entry("kafka.producers.analysis-dead-letter.enable.idempotence", true),
                Map.entry("kafka.producers.results-dead-letter.key.serializer",
                        "org.apache.kafka.common.serialization.StringSerializer"),
                Map.entry("kafka.producers.results-dead-letter.value.serializer",
                        "org.apache.kafka.common.serialization.ByteArraySerializer"),
                Map.entry("kafka.producers.results-dead-letter.acks", "all"),
                Map.entry("kafka.producers.results-dead-letter.enable.idempotence", true),
                Map.entry("kafka.producers.event-observation-dead-letter.key.serializer",
                        "org.apache.kafka.common.serialization.StringSerializer"),
                Map.entry("kafka.producers.event-observation-dead-letter.value.serializer",
                        "org.apache.kafka.common.serialization.ByteArraySerializer"),
                Map.entry("kafka.producers.event-observation-dead-letter.acks", "all"),
                Map.entry("kafka.producers.event-observation-dead-letter.enable.idempotence", true),
                Map.entry("kafka.consumers." + ANALYSIS_GROUP + ".key.deserializer",
                        "org.apache.kafka.common.serialization.StringDeserializer"),
                Map.entry("kafka.consumers." + ANALYSIS_GROUP + ".value.deserializer",
                        "org.apache.kafka.common.serialization.ByteArrayDeserializer"),
                Map.entry("kafka.consumers." + RESULTS_GROUP + ".key.deserializer",
                        "org.apache.kafka.common.serialization.StringDeserializer"),
                Map.entry("kafka.consumers." + RESULTS_GROUP + ".value.deserializer",
                        "org.apache.kafka.common.serialization.ByteArrayDeserializer"),
                Map.entry("kafka.consumers." + OBSERVATION_GROUP + ".key.deserializer",
                        "org.apache.kafka.common.serialization.StringDeserializer"),
                Map.entry("kafka.consumers." + OBSERVATION_GROUP + ".value.deserializer",
                        "org.apache.kafka.common.serialization.ByteArrayDeserializer"),
                Map.entry("signalharvester.kafka.raw-item-discovered-topic", RAW_TOPIC),
                Map.entry("signalharvester.kafka.item-analyzed-topic", ANALYZED_TOPIC),
                Map.entry("signalharvester.kafka.item-rejected-topic", REJECTED_TOPIC),
                Map.entry("signalharvester.analysis.enabled", true),
                Map.entry("signalharvester.analysis.consumer-group", ANALYSIS_GROUP),
                Map.entry("signalharvester.analysis.outbox.poll-interval", "50ms"),
                Map.entry("signalharvester.results.enabled", true),
                Map.entry("signalharvester.results.consumer-group", RESULTS_GROUP),
                Map.entry("signalharvester.event-observation.enabled", true),
                Map.entry("signalharvester.event-observation.consumer-group", OBSERVATION_GROUP),
                Map.entry("signalharvester.event-observation.sse.poll-interval", "50ms"),
                Map.entry("signalharvester.results.sse.poll-interval", "50ms"),
                Map.entry("signalharvester.results.sse.keepalive-interval", "1s"),
                Map.entry("signalharvester.results.sse.reconnect-delay", "100ms"),
                Map.entry("signalharvester.analysis.keyword-rules.keywords", List.of("java", "kafka", "postgresql")),
                Map.entry("signalharvester.analysis.keyword-rules.minimum-matches", 1),
                Map.entry("signalharvester.collection.max-concurrency", 2),
                Map.entry("signalharvester.collection.scheduler.enabled", false));
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

    private void respondWithJsonSourceContent(HttpExchange exchange) throws IOException {
        sourceRequests.incrementAndGet();
        byte[] payload = """
                {
                  "items": [
                    {
                      "id": "job-1",
                      "title": "Java Engineer",
                      "url": "/jobs/1",
                      "content": "Java Kafka PostgreSQL"
                    },
                    {
                      "id": "job-2",
                      "title": "Backend Engineer",
                      "url": "/jobs/2",
                      "content": "Distributed systems"
                    }
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
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
            statement.execute("DROP SCHEMA IF EXISTS results CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS event_observation CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS security CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }
}
