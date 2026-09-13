package io.signalharvester.collection.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.signalharvester.collection.run.CollectionRunHistory;
import io.signalharvester.collection.run.CollectionRunRequest;
import io.signalharvester.collection.run.CollectionRunResult;
import io.signalharvester.collection.run.CollectionRunStatus;
import io.signalharvester.collection.run.CollectionRunner;
import io.signalharvester.collection.run.CollectionSourceResult;
import io.signalharvester.collection.run.CollectionSourceStatus;
import io.signalharvester.collection.run.CollectionRunHistoryQuery;
import io.signalharvester.collection.run.CollectionRunNotFoundException;
import io.signalharvester.collection.run.CollectionRunService;
import io.signalharvester.configuration.api.SourceId;
import jakarta.inject.Singleton;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Server-level contract tests for the collection operational HTTP API. */
class CollectionRunControllerTest {

    private static final String SPEC_NAME = "collection-run-controller";
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    private EmbeddedServer server;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        server = ApplicationContext.run(EmbeddedServer.class, serverProperties(), "test");
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void shouldServeManualRunAndHistoryThroughBlockingHttpBoundary() throws Exception {
        HttpResponse<String> started = send("POST", "/api/v1/admin/collection-runs", """
                {
                  "monitoringProfileId": "profile-a",
                  "informationCategory": "JOB"
                }
                """);
        assertEquals(201, started.statusCode());
        assertTrue(started.body().contains("\"collectionRunId\":\"" + RUN_ID + "\""));
        assertTrue(started.body().contains("\"status\":\"SUCCEEDED\""));
        assertTrue(started.body().contains("\"sourceId\":\"" + SOURCE_ID + "\""));
        assertTrue(started.body().contains("\"failureMessage\":null"));

        TestCollectionRunner runner = server.getApplicationContext().getBean(TestCollectionRunner.class);
        assertEquals("profile-a", runner.lastRequest().monitoringProfileId());
        assertEquals("JOB", runner.lastRequest().informationCategory());
        assertTrue(runner.lastThread().isVirtual());
        assertFalse(runner.lastThread().getName().contains("EventLoop"));

        HttpResponse<String> recent = send("GET", "/api/v1/admin/collection-runs", null);
        assertEquals(200, recent.statusCode());
        assertTrue(recent.body().contains(RUN_ID.toString()));

        TestCollectionRunHistory history = server.getApplicationContext().getBean(TestCollectionRunHistory.class);
        assertEquals(50, history.lastLimit());
        assertTrue(history.lastThread().isVirtual());
        assertFalse(history.lastThread().getName().contains("EventLoop"));

        HttpResponse<String> fetched = send("GET", "/api/v1/admin/collection-runs/" + RUN_ID, null);
        assertEquals(200, fetched.statusCode());
        assertTrue(fetched.body().contains(RUN_ID.toString()));
    }

    @Test
    void shouldValidateRunRequestAndHistoryBoundsAndMapMissingRun() throws Exception {
        HttpResponse<String> invalidRequest = send("POST", "/api/v1/admin/collection-runs", """
                {
                  "monitoringProfileId": "   ",
                  "informationCategory": "JOB"
                }
                """);
        assertEquals(400, invalidRequest.statusCode());

        assertEquals(400, send("GET", "/api/v1/admin/collection-runs?limit=0", null).statusCode());
        assertEquals(400, send("GET", "/api/v1/admin/collection-runs?limit=201", null).statusCode());
        assertEquals(400, send("GET", "/api/v1/admin/collection-runs/not-a-uuid", null).statusCode());
        assertEquals(404, send(
                "GET",
                "/api/v1/admin/collection-runs/00000000-0000-0000-0000-000000000999",
                null).statusCode());
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(server.getURI().resolve(path));
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> serverProperties() {
        return Map.ofEntries(
                Map.entry("spec.name", SPEC_NAME),
                Map.entry("micronaut.server.port", -1),
                Map.entry("micronaut.executors.blocking.virtual", true));
    }

    private static CollectionRunResult runResult() {
        return new CollectionRunResult(
                RUN_ID.toString(),
                "profile-a",
                "JOB",
                Instant.parse("2026-09-12T10:00:00Z"),
                Instant.parse("2026-09-12T10:00:01Z"),
                CollectionRunStatus.SUCCEEDED,
                List.of(new CollectionSourceResult(
                        SourceId.of(SOURCE_ID),
                        CollectionSourceStatus.PUBLISHED,
                        Optional.of("raw-1"),
                        Optional.of("event-1"),
                        Optional.empty())));
    }

    @Singleton
    @Replaces(CollectionRunService.class)
    @Requires(property = "spec.name", value = SPEC_NAME)
    static final class TestCollectionRunner implements CollectionRunner {
        private final AtomicReference<CollectionRunRequest> lastRequest = new AtomicReference<>();
        private final AtomicReference<Thread> lastThread = new AtomicReference<>();

        @Override
        public CollectionRunResult run(CollectionRunRequest request) {
            lastRequest.set(request);
            lastThread.set(Thread.currentThread());
            return runResult();
        }

        CollectionRunRequest lastRequest() {
            return lastRequest.get();
        }

        Thread lastThread() {
            return lastThread.get();
        }
    }

    @Singleton
    @Replaces(CollectionRunHistoryQuery.class)
    @Requires(property = "spec.name", value = SPEC_NAME)
    static final class TestCollectionRunHistory implements CollectionRunHistory {
        private final AtomicInteger lastLimit = new AtomicInteger();
        private final AtomicReference<Thread> lastThread = new AtomicReference<>();

        @Override
        public List<CollectionRunResult> recent(int limit) {
            lastLimit.set(limit);
            lastThread.set(Thread.currentThread());
            return List.of(runResult());
        }

        @Override
        public CollectionRunResult get(UUID collectionRunId) {
            lastThread.set(Thread.currentThread());
            if (!RUN_ID.equals(collectionRunId)) {
                throw new CollectionRunNotFoundException(collectionRunId);
            }
            return runResult();
        }

        int lastLimit() {
            return lastLimit.get();
        }

        Thread lastThread() {
            return lastThread.get();
        }
    }
}
