package io.signalharvester.collection.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.signalharvester.collection.run.CollectionProfileNotFoundException;
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
import io.signalharvester.configuration.api.MonitoringProfileId;
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

/**
 * Verifies the operational HTTP contract implemented by {@link CollectionRunController}, including request
 * validation, response mapping, history lookup, status handling, and blocking-work offload.
 *
 * <p>Related specification: {@code backend-operational-admin-api}.</p>
 *
 * <p>Features: {@code COLLECTION.RUNS}, {@code CONTRACTS.HTTP}.</p>
 */
class CollectionRunControllerTest {

    private static final String SPEC_NAME = "collection-run-controller";
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID MISSING_RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000999");
    private static final UUID MISSING_PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000998");
    private static final UUID PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final String RAW_ITEM_ID = "raw-1";
    private static final String EVENT_ID = "event-1";

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

    /**
     * Serve manual run and history through blocking HTTP boundary.
     */
    @Test
    void shouldServeManualRunAndHistoryThroughBlockingHttpBoundary() throws Exception {
        HttpResponse<String> started = send("POST", "/api/v1/admin/collection-runs", """
                {
                  "monitoringProfileId": "%s"
                }
                """.formatted(PROFILE_ID));
        assertEquals(201, started.statusCode());
        assertTrue(started.body().contains("\"collectionRunId\":\"" + RUN_ID + "\""));
        assertTrue(started.body().contains("\"status\":\"SUCCEEDED\""));
        assertTrue(started.body().contains("\"sourceId\":\"" + SOURCE_ID + "\""));
        assertTrue(started.body().contains("\"failureMessage\":null"));

        TestCollectionRunner runner = server.getApplicationContext().getBean(TestCollectionRunner.class);
        assertEquals(MonitoringProfileId.of(PROFILE_ID), runner.lastRequest().monitoringProfileId());
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

    /**
     * Validate run request and history bounds and map missing run.
     */
    @Test
    void shouldValidateRunRequestAndHistoryBoundsAndMapMissingRun() throws Exception {
        HttpResponse<String> invalidRequest = send("POST", "/api/v1/admin/collection-runs", """
                {
                  "monitoringProfileId": "not-a-uuid"
                }
                """);
        assertEquals(400, invalidRequest.statusCode());

        HttpResponse<String> missingProfile = send("POST", "/api/v1/admin/collection-runs", """
                {
                  "monitoringProfileId": "%s"
                }
                """.formatted(MISSING_PROFILE_ID));
        assertEquals(404, missingProfile.statusCode());

        assertEquals(400, send("GET", "/api/v1/admin/collection-runs?limit=0", null).statusCode());
        assertEquals(400, send("GET", "/api/v1/admin/collection-runs?limit=201", null).statusCode());
        assertEquals(400, send("GET", "/api/v1/admin/collection-runs/not-a-uuid", null).statusCode());
        assertEquals(404, send(
                "GET",
                "/api/v1/admin/collection-runs/" + MISSING_RUN_ID,
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
                Map.entry("micronaut.executors.blocking.virtual", true),
                Map.entry("signalharvester.collection.scheduler.enabled", false));
    }

    private static CollectionRunResult runResult() {
        return new CollectionRunResult(
                RUN_ID.toString(),
                PROFILE_ID.toString(),
                "JOB",
                Instant.parse("2026-09-12T10:00:00Z"),
                Instant.parse("2026-09-12T10:00:01Z"),
                CollectionRunStatus.SUCCEEDED,
                List.of(new CollectionSourceResult(
                        SourceId.of(SOURCE_ID),
                        CollectionSourceStatus.PUBLISHED,
                        Optional.of(RAW_ITEM_ID),
                        Optional.of(EVENT_ID),
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
            if (MonitoringProfileId.of(MISSING_PROFILE_ID).equals(request.monitoringProfileId())) {
                throw new CollectionProfileNotFoundException(request.monitoringProfileId());
            }
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
