package io.signalharvester.collection.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.signalharvester.collection.sourcetest.SourceTestPreviewItem;
import io.signalharvester.collection.sourcetest.SourceTestResult;
import io.signalharvester.collection.sourcetest.SourceTestService;
import io.signalharvester.collection.sourcetest.SourceTestSourceNotFoundException;
import io.signalharvester.collection.sourcetest.SourceTestStatus;
import io.signalharvester.collection.sourcetest.SourceTester;
import io.signalharvester.configuration.api.SourceId;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies source-test HTTP mapping, missing-source handling, and blocking-executor offload.
 *
 * <p>Features: {@code COLLECTION.SOURCE_TEST}, {@code CONTRACTS.HTTP}.</p>
 */
class SourceTestControllerTest {

    private static final String SPEC_NAME = "source-test-controller";
    private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000886");
    private static final UUID MISSING_SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000887");

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

    /** Return bounded diagnostic JSON from the source-test endpoint on the blocking executor. */
    @Test
    void shouldServeSourceTestThroughBlockingBoundary() throws Exception {
        HttpResponse<String> response = send("/api/v1/sources/" + SOURCE_ID + "/test");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"SUCCEEDED\""));
        assertTrue(response.body().contains("\"candidateItemCount\":1"));
        assertTrue(response.body().contains("\"contentPreview\":\"preview\""));
        assertTrue(response.body().contains("\"failureMessage\":null"));

        TestSourceTester tester = server.getApplicationContext().getBean(TestSourceTester.class);
        assertTrue(tester.lastThread().isVirtual());
        assertFalse(tester.lastThread().getName().contains("EventLoop"));
    }

    /** Map invalid and missing source identifiers to public 400 and 404 responses. */
    @Test
    void shouldValidateSourceIdentityAndMapMissingSource() throws Exception {
        assertEquals(400, send("/api/v1/sources/not-a-uuid/test").statusCode());
        assertEquals(404, send("/api/v1/sources/" + MISSING_SOURCE_ID + "/test").statusCode());
    }

    private HttpResponse<String> send(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(server.getURI().resolve(path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> serverProperties() {
        return Map.ofEntries(
                Map.entry("spec.name", SPEC_NAME),
                Map.entry("micronaut.server.port", -1),
                Map.entry("micronaut.executors.blocking.virtual", true),
                Map.entry("signalharvester.collection.scheduler.enabled", false));
    }

    @Singleton
    @Replaces(SourceTestService.class)
    @Requires(property = "spec.name", value = SPEC_NAME)
    static final class TestSourceTester implements SourceTester {
        private final AtomicReference<Thread> lastThread = new AtomicReference<>();

        @Override
        public SourceTestResult test(SourceId sourceId) {
            lastThread.set(Thread.currentThread());
            if (MISSING_SOURCE_ID.equals(sourceId.value())) {
                throw new SourceTestSourceNotFoundException(sourceId);
            }
            return new SourceTestResult(
                    sourceId,
                    SourceTestStatus.SUCCEEDED,
                    Optional.of(200),
                    Optional.of("text/plain"),
                    7,
                    3,
                    1,
                    1,
                    List.of(new SourceTestPreviewItem(
                            Optional.of("item-1"),
                            Optional.of("Title"),
                            URI.create("https://example.test/item/1"),
                            "preview",
                            "text/plain; charset=UTF-8",
                            Optional.of(Instant.parse("2026-09-14T08:00:00Z")),
                            false)),
                    Optional.empty());
        }

        Thread lastThread() {
            return lastThread.get();
        }
    }
}
