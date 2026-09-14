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
import jakarta.inject.Singleton;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies bounded Event Explorer REST filters and blocking-work offload for {@link EventObservationController}. */
class EventObservationControllerTest {

    private static final String SPEC_NAME = "event-observation-controller";
    private EmbeddedServer server;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        server = ApplicationContext.run(EmbeddedServer.class, Map.ofEntries(
                Map.entry("spec.name", SPEC_NAME),
                Map.entry("micronaut.server.port", -1),
                Map.entry("micronaut.executors.blocking.virtual", true),
                Map.entry("kafka.enabled", false),
                Map.entry("signalharvester.event-observation.enabled", false)), "test");
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    /** Serve filtered technical history through a blocking virtual thread and serialize decoded payload metadata. */
    @Test
    void shouldServeFilteredHistoryOnBlockingVirtualThread() throws Exception {
        HttpResponse<String> response = send("/api/v1/events?limit=25&eventType=analysis.item-analyzed.v1"
                + "&producer=analysis&topic=topic-a&collectionRunId=run-1&itemId=norm-1&traceId=0123456789abcdef0123456789abcdef");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"eventId\":\"event-1\""));
        assertTrue(response.body().contains("\"topic\":\"topic-a\""));
        assertTrue(response.body().contains("\"normalizedItemId\":\"norm-1\""));
        assertTrue(response.body().contains("\"score\":90"));

        TestEventObservationQuery query = server.getApplicationContext().getBean(TestEventObservationQuery.class);
        assertEquals(25, query.lastLimit());
        assertEquals(Optional.of("analysis.item-analyzed.v1"), query.lastCriteria().eventType());
        assertEquals(Optional.of("run-1"), query.lastCriteria().collectionRunId());
        assertEquals(Optional.of("norm-1"), query.lastCriteria().itemId());
        assertTrue(query.lastThread().isVirtual());
        assertFalse(query.lastThread().getName().contains("EventLoop"));

        assertEquals(400, send("/api/v1/events?limit=0").statusCode());
        assertEquals(400, send("/api/v1/events?limit=501").statusCode());
    }

    private HttpResponse<String> send(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(server.getURI().resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static ObservedEvent event() {
        return new ObservedEvent(
                7L,
                "event-1",
                "analysis.item-analyzed.v1",
                Instant.parse("2026-09-14T10:00:00Z"),
                Instant.parse("2026-09-14T10:00:01Z"),
                "run-1",
                Optional.of("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"),
                "analysis",
                "v1",
                "topic-a",
                0,
                42L,
                "norm-1",
                "ItemAnalyzed",
                Optional.of("source-event-1"),
                Optional.of("raw-1"),
                Optional.of("norm-1"),
                Optional.of("source-1"),
                Optional.of("profile-1"),
                Optional.of("JOB"),
                Optional.empty(),
                Optional.of("Senior Java Engineer"),
                Optional.of("https://example.test/jobs/1"),
                Optional.of("text/plain"),
                Optional.of(true),
                Optional.of("MATCHED"),
                OptionalInt.of(90),
                Optional.of("keyword-v1"),
                Optional.empty(),
                Optional.of("Matched deterministic keywords"));
    }

    @Singleton
    @Replaces(EventObservationService.class)
    @Requires(property = "spec.name", value = SPEC_NAME)
    static final class TestEventObservationQuery implements EventObservationQuery {
        private final AtomicReference<EventObservationCriteria> criteria = new AtomicReference<>();
        private final AtomicReference<Integer> limit = new AtomicReference<>();
        private final AtomicReference<Thread> thread = new AtomicReference<>();

        @Override
        public List<ObservedEvent> recent(EventObservationCriteria criteria, int limit) {
            this.criteria.set(criteria);
            this.limit.set(limit);
            this.thread.set(Thread.currentThread());
            return List.of(event());
        }

        @Override
        public long currentCursor() {
            return 7;
        }

        @Override
        public EventObservationLiveBatch pollAfter(long cursor, EventObservationCriteria criteria, int limit) {
            return new EventObservationLiveBatch(cursor, List.of());
        }

        EventObservationCriteria lastCriteria() {
            return criteria.get();
        }

        int lastLimit() {
            return limit.get();
        }

        Thread lastThread() {
            return thread.get();
        }
    }
}
