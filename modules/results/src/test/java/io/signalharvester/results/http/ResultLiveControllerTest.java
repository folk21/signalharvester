package io.signalharvester.results.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.awaitility.Awaitility.await;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.signalharvester.results.application.ResultLiveBatch;
import io.signalharvester.results.application.ResultLiveCriteria;
import io.signalharvester.results.application.ResultLiveQuery;
import io.signalharvester.results.application.ResultLiveQueryService;
import io.signalharvester.results.application.ResultLiveUpdate;
import io.signalharvester.results.application.ResultSummary;
import io.signalharvester.results.testing.ResultSummaryFixture;
import jakarta.inject.Singleton;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link ResultLiveController} SSE framing, filter binding, resume cursors, and blocking-query offload.
 *
 * <p>Related specification: {@code backend-results-sse-live-delivery}.</p>
 *
 * <p>Features: {@code RESULTS.LIVE}, {@code CONTRACTS.HTTP}.</p>
 */
class ResultLiveControllerTest {

    private static final String SPEC_NAME = "results-live-controller";
    private static final String PROFILE_ID = "profile-live";
    private static final String SOURCE_ID = "source-live";
    private static final String NORMALIZED_ITEM_ID = "c".repeat(64);
    private static final long READY_CURSOR = 41L;
    private static final long RESULT_CURSOR = 42L;

    private EmbeddedServer server;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        server = ApplicationContext.run(EmbeddedServer.class, serverProperties(), "test");
        server.getApplicationContext().getBean(ResultLiveController.class);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    /**
     * Emit an initial ready cursor followed by a live result while polling on the blocking virtual-thread executor.
     */
    @Test
    void shouldStreamReadyAndResultEventsOffEventLoop() throws Exception {
        String path = "/api/v1/results/stream?monitoringProfileId=" + PROFILE_ID
                + "&sourceId=" + SOURCE_ID
                + "&informationCategory=JOB&relevant=true&classification=MATCHED";
        HttpRequest request = HttpRequest.newBuilder(server.getURI().resolve(path))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertSseOk(response);
        assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"));

        List<String> lines;
        try (InputStream body = response.body()) {
            lines = readUntil(body, NORMALIZED_ITEM_ID, Duration.ofSeconds(5));
        }

        String stream = String.join("\n", lines)
                .replace("event: ", "event:")
                .replace("id: ", "id:");
        assertTrue(stream.contains("event:ready"));
        assertTrue(stream.contains("id:" + READY_CURSOR));
        assertTrue(stream.contains("\"cursor\":" + READY_CURSOR + ",\"result\":null"));
        assertTrue(stream.contains("event:result"));
        assertTrue(stream.contains("id:" + RESULT_CURSOR));
        assertTrue(stream.contains("\"normalizedItemId\":\"" + NORMALIZED_ITEM_ID + "\""));

        TestResultLiveQuery query = server.getApplicationContext().getBean(TestResultLiveQuery.class);
        ResultLiveCriteria criteria = query.lastCriteria();
        assertEquals(Optional.of(PROFILE_ID), criteria.monitoringProfileId());
        assertEquals(Optional.of(SOURCE_ID), criteria.sourceId());
        assertEquals(Optional.of("JOB"), criteria.informationCategory());
        assertEquals(Optional.of(true), criteria.relevant());
        assertEquals(Optional.of("MATCHED"), criteria.classification());
        assertTrue(query.lastThread().isVirtual());
        assertFalse(query.lastThread().getName().contains("EventLoop"));
    }

    /** Resume directly after Last-Event-ID and reject a negative cursor. */
    @Test
    void shouldResumeFromLastEventIdAndValidateCursor() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(server.getURI().resolve("/api/v1/results/stream"))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "text/event-stream")
                .header("Last-Event-ID", Long.toString(READY_CURSOR))
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertSseOk(response);
        List<String> lines;
        try (InputStream body = response.body()) {
            lines = readUntil(body, NORMALIZED_ITEM_ID, Duration.ofSeconds(5));
        }
        String stream = String.join("\n", lines)
                .replace("event: ", "event:")
                .replace("id: ", "id:");
        assertFalse(stream.contains("event:ready"));
        assertTrue(stream.contains("event:result"));

        HttpRequest invalid = HttpRequest.newBuilder(server.getURI().resolve("/api/v1/results/stream"))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "text/event-stream")
                .header("Last-Event-ID", "-1")
                .GET()
                .build();
        assertEquals(400, client.send(invalid, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    private static void assertSseOk(HttpResponse<InputStream> response) throws Exception {
        if (response.statusCode() == 200) {
            return;
        }
        try (InputStream body = response.body()) {
            String responseBody = new String(body.readAllBytes(), StandardCharsets.UTF_8);
            throw new AssertionError(
                    "Expected SSE HTTP 200 but received status=" + response.statusCode()
                            + ", headers=" + response.headers().map()
                            + ", body=" + responseBody);
        }
    }

    private static List<String> readUntil(InputStream input, String target, Duration timeout) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();
        await()
                .pollInterval(Duration.ofMillis(10))
                .atMost(timeout)
                .until(() -> readAvailableUntil(reader, lines, target));
        return lines;
    }

    private static boolean readAvailableUntil(BufferedReader reader, List<String> lines, String target) throws IOException {
        while (reader.ready()) {
            String line = reader.readLine();
            if (line == null) {
                return false;
            }
            lines.add(line);
            if (line.contains(target)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> serverProperties() {
        return Map.ofEntries(
                Map.entry("spec.name", SPEC_NAME),
                Map.entry("micronaut.server.port", -1),
                Map.entry("micronaut.executors.blocking.virtual", true),
                Map.entry("kafka.enabled", false),
                Map.entry("signalharvester.results.enabled", false),
                Map.entry("signalharvester.results.sse.poll-interval", "10ms"),
                Map.entry("signalharvester.results.sse.keepalive-interval", "100ms"),
                Map.entry("signalharvester.results.sse.reconnect-delay", "100ms"),
                Map.entry("signalharvester.results.sse.batch-size", 10));
    }

    private static ResultSummary summary() {
        return ResultSummaryFixture.resultSummary(PROFILE_ID, NORMALIZED_ITEM_ID)
                .source(SOURCE_ID)
                .externalId("external-live")
                .title("Live Java Engineer")
                .url("https://example.test/jobs/live")
                .score(95)
                .attributes(Map.of("location", "Remote"))
                .tags(List.of("java"))
                .explanation("Matched java")
                .analyzedAt(Instant.parse("2026-09-14T10:00:00Z"))
                .build();
    }

    @Singleton
    @Replaces(ResultLiveQueryService.class)
    @Requires(property = "spec.name", value = SPEC_NAME)
    static final class TestResultLiveQuery implements ResultLiveQuery {
        private final AtomicReference<ResultLiveCriteria> lastCriteria = new AtomicReference<>();
        private final AtomicReference<Thread> lastThread = new AtomicReference<>();

        @Override
        public long currentCursor() {
            lastThread.set(Thread.currentThread());
            return READY_CURSOR;
        }

        @Override
        public ResultLiveBatch pollAfter(long cursor, ResultLiveCriteria criteria, int maxItems) {
            lastThread.set(Thread.currentThread());
            lastCriteria.set(criteria);
            if (cursor < RESULT_CURSOR) {
                return new ResultLiveBatch(
                        RESULT_CURSOR,
                        List.of(new ResultLiveUpdate(RESULT_CURSOR, summary())));
            }
            return new ResultLiveBatch(cursor, List.of());
        }

        ResultLiveCriteria lastCriteria() {
            return lastCriteria.get();
        }

        Thread lastThread() {
            return lastThread.get();
        }
    }
}
