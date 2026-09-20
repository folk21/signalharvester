package io.signalharvester.eventobservation.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.signalharvester.eventobservation.application.EventObservationCriteria;
import io.signalharvester.eventobservation.application.EventObservationLiveBatch;
import io.signalharvester.eventobservation.application.EventObservationQuery;
import io.signalharvester.eventobservation.application.EventObservationService;
import io.signalharvester.eventobservation.model.ObservedEvent;
import io.signalharvester.eventobservation.testing.ObservedEventFixture;
import jakarta.inject.Singleton;
import java.io.BufferedReader;
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
 * Verifies Event Explorer SSE framing, filters, resume cursors, and blocking-query offload.
 *
 * <p>Features: {@code DIAGNOSTICS.EVENT_OBSERVATION}, {@code CONTRACTS.HTTP}.</p>
 */
class EventObservationLiveControllerTest {

    private static final String SPEC_NAME = "event-observation-live-controller";
    private static final long READY_CURSOR = 41L;
    private static final long EVENT_CURSOR = 42L;

    private EmbeddedServer server;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        server = ApplicationContext.run(EmbeddedServer.class, serverProperties(), "test");
        server.getApplicationContext().getBean(EventObservationLiveController.class);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    /** Emit ready then one observed event while querying durable history on a blocking virtual thread. */
    @Test
    void shouldStreamReadyAndObservedEventOffEventLoop() throws Exception {
        String path = "/api/v1/events/stream?eventType=analysis.item-analyzed.v1&producer=analysis"
                + "&topic=topic-a&collectionRunId=run-1&itemId=norm-1&traceId=0123456789abcdef0123456789abcdef";
        HttpResponse<InputStream> response = client.send(
                HttpRequest.newBuilder(server.getURI().resolve(path))
                        .timeout(Duration.ofSeconds(5))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertSseOk(response);

        List<String> lines;
        try (InputStream body = response.body()) {
            lines = readUntil(body, "analyzed-event", Duration.ofSeconds(5));
        }
        String stream = String.join("\n", lines).replace("event: ", "event:").replace("id: ", "id:");
        assertTrue(stream.contains("event:ready"));
        assertTrue(stream.contains("id:" + READY_CURSOR));
        assertTrue(stream.contains("event:event"));
        assertTrue(stream.contains("id:" + EVENT_CURSOR));
        assertTrue(stream.contains("\"eventId\":\"analyzed-event\""));

        TestEventObservationQuery query = server.getApplicationContext().getBean(TestEventObservationQuery.class);
        assertEquals(Optional.of("analysis.item-analyzed.v1"), query.lastCriteria().eventType());
        assertEquals(Optional.of("run-1"), query.lastCriteria().collectionRunId());
        assertTrue(query.lastThread().isVirtual());
        assertFalse(query.lastThread().getName().contains("EventLoop"));
    }

    /** Resume after Last-Event-ID without a second ready event and reject a negative cursor. */
    @Test
    void shouldResumeAndValidateCursor() throws Exception {
        HttpResponse<InputStream> response = client.send(
                HttpRequest.newBuilder(server.getURI().resolve("/api/v1/events/stream"))
                        .timeout(Duration.ofSeconds(5))
                        .header("Accept", "text/event-stream")
                        .header("Last-Event-ID", Long.toString(READY_CURSOR))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertSseOk(response);
        List<String> lines;
        try (InputStream body = response.body()) {
            lines = readUntil(body, "analyzed-event", Duration.ofSeconds(5));
        }
        String stream = String.join("\n", lines).replace("event: ", "event:");
        assertFalse(stream.contains("event:ready"));
        assertTrue(stream.contains("event:event"));

        HttpRequest invalid = HttpRequest.newBuilder(server.getURI().resolve("/api/v1/events/stream"))
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
            throw new AssertionError("Expected SSE HTTP 200 but received status=" + response.statusCode()
                    + ", headers=" + response.headers().map()
                    + ", body=" + new String(body.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static List<String> readUntil(InputStream input, String target, Duration timeout) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        Instant deadline = Instant.now().plus(timeout);
        List<String> lines = new ArrayList<>();
        while (Instant.now().isBefore(deadline)) {
            if (!reader.ready()) {
                Thread.sleep(10);
                continue;
            }
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            lines.add(line);
            if (line.contains(target)) {
                return lines;
            }
        }
        throw new AssertionError("Timed out waiting for SSE content " + target + ": " + lines);
    }

    private static Map<String, Object> serverProperties() {
        return Map.ofEntries(
                Map.entry("spec.name", SPEC_NAME),
                Map.entry("micronaut.server.port", -1),
                Map.entry("micronaut.executors.blocking.virtual", true),
                Map.entry("kafka.enabled", false),
                Map.entry("signalharvester.event-observation.enabled", false),
                Map.entry("signalharvester.event-observation.sse.poll-interval", "10ms"),
                Map.entry("signalharvester.event-observation.sse.keepalive-interval", "100ms"),
                Map.entry("signalharvester.event-observation.sse.reconnect-delay", "100ms"),
                Map.entry("signalharvester.event-observation.sse.batch-size", 10));
    }

    private static ObservedEvent event() {
        return ObservedEventFixture.observedEvent(EVENT_CURSOR, "analyzed-event", "analysis.item-analyzed.v1")
                .traceparent("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01")
                .topic("topic-a")
                .offset(7L)
                .key("norm-1")
                .payloadType("ItemAnalyzed")
                .sourceEventId("raw-event")
                .rawItemId("raw-1")
                .normalizedItemId("norm-1")
                .title("Java Engineer")
                .url("https://example.test/jobs/1")
                .contentType("text/plain")
                .relevant(true)
                .classification("MATCHED")
                .score(90)
                .analyzer("keyword-v1")
                .explanation("Matched Java")
                .build();
    }

    @Singleton
    @Replaces(EventObservationService.class)
    @Requires(property = "spec.name", value = SPEC_NAME)
    static final class TestEventObservationQuery implements EventObservationQuery {
        private final AtomicReference<EventObservationCriteria> criteria = new AtomicReference<>();
        private final AtomicReference<Thread> thread = new AtomicReference<>();

        @Override
        public List<ObservedEvent> recent(EventObservationCriteria criteria, int limit) {
            return List.of();
        }

        @Override
        public long currentCursor() {
            thread.set(Thread.currentThread());
            return READY_CURSOR;
        }

        @Override
        public EventObservationLiveBatch pollAfter(long cursor, EventObservationCriteria criteria, int limit) {
            thread.set(Thread.currentThread());
            this.criteria.set(criteria);
            if (cursor < EVENT_CURSOR) {
                return new EventObservationLiveBatch(EVENT_CURSOR, List.of(event()));
            }
            return new EventObservationLiveBatch(cursor, List.of());
        }

        EventObservationCriteria lastCriteria() {
            return criteria.get();
        }

        Thread lastThread() {
            return thread.get();
        }
    }
}
