package io.signalharvester.analysis.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.signalharvester.analysis.application.AnalysisItemInspection;
import io.signalharvester.analysis.application.AnalysisItemInspectionQuery;
import io.signalharvester.analysis.application.AnalysisItemInspectionService;
import jakarta.inject.Singleton;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the HTTP contract implemented by {@link AnalysisItemInspectionController}, including validation,
 * status mapping, response shape, and blocking-work offload through the application query boundary.
 *
 * <p>Related specification: {@code backend-operational-admin-api}.</p>
 */
class AnalysisItemInspectionControllerTest {

    private static final String SPEC_NAME = "analysis-item-inspection-controller";
    private static final String NORMALIZED_ITEM_ID = "a".repeat(64);
    private static final String MISSING_NORMALIZED_ITEM_ID = "b".repeat(64);
    private static final String PROFILE_ID = "profile-a";
    private static final String SOURCE_ID = "source-a";

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
     * Serve bounded inspection and forward optional filters on blocking thread.
     */
    @Test
    void shouldServeBoundedInspectionAndForwardOptionalFiltersOnBlockingThread() throws Exception {
        HttpResponse<String> recent = send("GET", "/api/v1/admin/analysis/items", null);
        assertEquals(200, recent.statusCode());
        assertTrue(recent.body().contains("\"normalizedItemId\":\"" + NORMALIZED_ITEM_ID + "\""));
        assertTrue(recent.body().contains("\"externalId\":null"));

        TestAnalysisItemInspectionQuery query = server.getApplicationContext().getBean(TestAnalysisItemInspectionQuery.class);
        assertEquals(50, query.lastLimit());
        assertEquals(Optional.empty(), query.lastProfile());
        assertEquals(Optional.empty(), query.lastSource());
        assertTrue(query.lastThread().isVirtual());
        assertFalse(query.lastThread().getName().contains("EventLoop"));

        String path = "/api/v1/admin/analysis/items?limit=10&monitoringProfileId=" + PROFILE_ID
                + "&sourceId=" + SOURCE_ID;
        assertEquals(200, send("GET", path, null).statusCode());
        assertEquals(10, query.lastLimit());
        assertEquals(Optional.of(PROFILE_ID), query.lastProfile());
        assertEquals(Optional.of(SOURCE_ID), query.lastSource());

        HttpResponse<String> fetched = send(
                "GET",
                "/api/v1/admin/analysis/items/" + NORMALIZED_ITEM_ID + "?monitoringProfileId=" + PROFILE_ID,
                null);
        assertEquals(200, fetched.statusCode());
        assertTrue(fetched.body().contains(NORMALIZED_ITEM_ID));
    }

    /**
     * Validate inspection parameters and return not found.
     */
    @Test
    void shouldValidateInspectionParametersAndReturnNotFound() throws Exception {
        assertEquals(400, send("GET", "/api/v1/admin/analysis/items?limit=0", null).statusCode());
        assertEquals(400, send("GET", "/api/v1/admin/analysis/items?limit=201", null).statusCode());
        assertEquals(400, send(
                "GET",
                "/api/v1/admin/analysis/items/not-a-hash?monitoringProfileId=" + PROFILE_ID,
                null).statusCode());

        String blankProfile = URLEncoder.encode("   ", StandardCharsets.UTF_8);
        assertEquals(400, send(
                "GET",
                "/api/v1/admin/analysis/items/" + NORMALIZED_ITEM_ID + "?monitoringProfileId=" + blankProfile,
                null).statusCode());

        assertEquals(404, send(
                "GET",
                "/api/v1/admin/analysis/items/" + MISSING_NORMALIZED_ITEM_ID + "?monitoringProfileId=" + PROFILE_ID,
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
                Map.entry("signalharvester.analysis.enabled", false),
                Map.entry("signalharvester.analysis.keyword-rules.keywords", List.of("java")));
    }

    private static AnalysisItemInspection inspection() {
        return new AnalysisItemInspection(
                PROFILE_ID,
                NORMALIZED_ITEM_ID,
                SOURCE_ID,
                Optional.empty(),
                "https://example.test/item",
                "raw-first",
                "event-first",
                Instant.parse("2026-09-12T10:00:00Z"),
                "raw-last",
                "event-last",
                Instant.parse("2026-09-12T10:05:00Z"),
                2);
    }

    @Singleton
    @Replaces(AnalysisItemInspectionService.class)
    @Requires(property = "spec.name", value = SPEC_NAME)
    static final class TestAnalysisItemInspectionQuery implements AnalysisItemInspectionQuery {
        private final AtomicInteger lastLimit = new AtomicInteger();
        private final AtomicReference<Optional<String>> lastProfile = new AtomicReference<>(Optional.empty());
        private final AtomicReference<Optional<String>> lastSource = new AtomicReference<>(Optional.empty());
        private final AtomicReference<Thread> lastThread = new AtomicReference<>();

        @Override
        public List<AnalysisItemInspection> recent(
                int limit,
                Optional<String> monitoringProfileId,
                Optional<String> sourceId) {
            lastLimit.set(limit);
            lastProfile.set(monitoringProfileId);
            lastSource.set(sourceId);
            lastThread.set(Thread.currentThread());
            return List.of(inspection());
        }

        @Override
        public Optional<AnalysisItemInspection> find(String monitoringProfileId, String normalizedItemId) {
            lastThread.set(Thread.currentThread());
            return NORMALIZED_ITEM_ID.equals(normalizedItemId)
                    ? Optional.of(inspection())
                    : Optional.empty();
        }

        int lastLimit() {
            return lastLimit.get();
        }

        Optional<String> lastProfile() {
            return lastProfile.get();
        }

        Optional<String> lastSource() {
            return lastSource.get();
        }

        Thread lastThread() {
            return lastThread.get();
        }
    }
}
