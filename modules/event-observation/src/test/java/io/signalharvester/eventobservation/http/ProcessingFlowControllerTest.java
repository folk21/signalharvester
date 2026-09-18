package io.signalharvester.eventobservation.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.signalharvester.eventobservation.application.ProcessingFlow;
import io.signalharvester.eventobservation.application.ProcessingFlowNotFoundException;
import io.signalharvester.eventobservation.application.ProcessingFlowQuery;
import io.signalharvester.eventobservation.application.ProcessingFlowService;
import jakarta.inject.Singleton;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies processing-flow HTTP routing, serialization, 404 mapping, and blocking-work offload.
 *
 * <p>Features: {@code DIAGNOSTICS.PROCESSING_FLOW}, {@code CONTRACTS.HTTP}.</p>
 */
class ProcessingFlowControllerTest {

    private static final String SPEC_NAME = "processing-flow-controller";
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

    /** Serve run and item graphs on a blocking virtual thread with stable graph metadata. */
    @Test
    void shouldServeRunAndItemFlowsOffEventLoop() throws Exception {
        HttpResponse<String> run = send("/api/v1/flows/collection-runs/run-1");
        assertEquals(200, run.statusCode());
        assertTrue(run.body().contains("\"scope\":\"COLLECTION_RUN\""));
        assertTrue(run.body().contains("\"stage\":\"ANALYSIS\""));
        assertTrue(run.body().contains("\"evidence\":\"OBSERVED_EVENT\""));
        assertTrue(run.body().contains("\"durationMs\":1500"));

        HttpResponse<String> item = send("/api/v1/flows/collection-runs/run-1/items/norm-1");
        assertEquals(200, item.statusCode());
        assertTrue(item.body().contains("\"scope\":\"ITEM\""));
        assertTrue(item.body().contains("\"itemId\":\"norm-1\""));

        TestProcessingFlowQuery query = server.getApplicationContext().getBean(TestProcessingFlowQuery.class);
        assertEquals("run-1", query.lastCollectionRunId());
        assertEquals(Optional.of("norm-1"), query.lastItemId());
        assertTrue(query.lastThread().isVirtual());
        assertFalse(query.lastThread().getName().contains("EventLoop"));
    }

    /** Map missing retained run or item history to HTTP 404. */
    @Test
    void shouldReturnNotFoundForUnknownFlowScope() throws Exception {
        assertEquals(404, send("/api/v1/flows/collection-runs/missing").statusCode());
        assertEquals(404, send("/api/v1/flows/collection-runs/run-1/items/missing").statusCode());
    }

    private HttpResponse<String> send(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(server.getURI().resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static ProcessingFlow flow(ProcessingFlow.Scope scope, Optional<String> itemId) {
        ProcessingFlow.Node analysis = new ProcessingFlow.Node(
                "event:analysis-1:analysis",
                "raw-event-1",
                ProcessingFlow.Stage.ANALYSIS,
                ProcessingFlow.NodeStatus.COMPLETED,
                ProcessingFlow.Evidence.OBSERVED_EVENT,
                Optional.of(Instant.parse("2026-09-14T10:00:02Z")),
                Optional.of("analysis-1"),
                Optional.of("analysis.item-analyzed.v1"),
                Optional.of("analysis"),
                Optional.of("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"),
                Optional.of("raw-event-1"),
                Optional.of("raw-1"),
                Optional.of("norm-1"),
                Optional.of("source-1"),
                Optional.of("profile-1"),
                Optional.of("MATCHED"),
                OptionalInt.of(90),
                Optional.empty());
        return new ProcessingFlow(
                scope,
                "run-1",
                itemId,
                ProcessingFlow.State.TERMINAL_EVENT_REACHED,
                2,
                List.of("0123456789abcdef0123456789abcdef"),
                List.of(analysis),
                List.of(new ProcessingFlow.Edge(
                        "event:raw-1:raw-kafka",
                        analysis.id(),
                        ProcessingFlow.EdgeKind.ASYNC_PROCESSING,
                        OptionalLong.of(1_500L))),
                List.of(ProcessingFlow.Limitation.RESULTS_PERSISTENCE_NOT_OBSERVED));
    }

    @Singleton
    @Replaces(ProcessingFlowService.class)
    @Requires(property = "spec.name", value = SPEC_NAME)
    static final class TestProcessingFlowQuery implements ProcessingFlowQuery {
        private final AtomicReference<String> collectionRunId = new AtomicReference<>();
        private final AtomicReference<String> itemId = new AtomicReference<>();
        private final AtomicReference<Thread> thread = new AtomicReference<>();

        @Override
        public ProcessingFlow collectionRun(String collectionRunId) {
            capture(collectionRunId, null);
            if ("missing".equals(collectionRunId)) {
                throw new ProcessingFlowNotFoundException("missing");
            }
            return flow(ProcessingFlow.Scope.COLLECTION_RUN, Optional.empty());
        }

        @Override
        public ProcessingFlow item(String collectionRunId, String itemId) {
            capture(collectionRunId, itemId);
            if ("missing".equals(itemId)) {
                throw new ProcessingFlowNotFoundException("missing");
            }
            return flow(ProcessingFlow.Scope.ITEM, Optional.of(itemId));
        }

        private void capture(String collectionRunId, String itemId) {
            this.collectionRunId.set(collectionRunId);
            this.itemId.set(itemId);
            this.thread.set(Thread.currentThread());
        }

        String lastCollectionRunId() {
            return collectionRunId.get();
        }

        Optional<String> lastItemId() {
            return Optional.ofNullable(itemId.get());
        }

        Thread lastThread() {
            return thread.get();
        }
    }
}
