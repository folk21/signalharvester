package io.signalharvester.results.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.signalharvester.results.application.ResultDetail;
import io.signalharvester.results.application.ResultQuery;
import io.signalharvester.results.application.ResultQueryCriteria;
import io.signalharvester.results.application.ResultQueryService;
import io.signalharvester.results.application.ResultSummary;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the HTTP contract implemented by {@link ResultController}, including bounded result-feed filters,
 * detail status mapping, nullable JSON fields, and blocking-work offload through {@link ResultQuery}.
 *
 * <p>Related specification: {@code backend-results-rest-api}.</p>
 */
class ResultControllerTest {

    private static final String SPEC_NAME = "results-controller";
    private static final String PROFILE_ID = "profile-results";
    private static final String SOURCE_ID = "source-results";
    private static final String NORMALIZED_ITEM_ID = "a".repeat(64);
    private static final String MISSING_NORMALIZED_ITEM_ID = "b".repeat(64);
    private static final Instant ANALYZED_AT = Instant.parse("2026-09-13T10:05:00Z");

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
     * Serve default and filtered result-feed requests on a blocking virtual thread.
     */
    @Test
    void shouldServeDefaultAndFilteredFeedOnBlockingVirtualThread() throws Exception {
        HttpResponse<String> response = send("/api/v1/results");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"normalizedItemId\":\"" + NORMALIZED_ITEM_ID + "\""));
        assertTrue(response.body().contains("\"externalId\":null"));
        assertFalse(response.body().contains("normalizedContent"));
        assertTrue(response.body().contains("\"attributes\":{\"location\":\"Remote\"}"));

        TestResultQuery query = server.getApplicationContext().getBean(TestResultQuery.class);
        ResultQueryCriteria defaults = query.lastCriteria();
        assertEquals(50, defaults.limit());
        assertEquals(Optional.empty(), defaults.monitoringProfileId());
        assertEquals(Optional.empty(), defaults.relevant());
        assertTrue(query.lastThread().isVirtual());
        assertFalse(query.lastThread().getName().contains("EventLoop"));

        String path = "/api/v1/results?limit=10&monitoringProfileId=" + PROFILE_ID
                + "&sourceId=" + SOURCE_ID
                + "&informationCategory=JOB&relevant=true&classification=MATCHED"
                + "&analyzedFrom=2026-09-13T10:00:00Z&analyzedTo=2026-09-13T11:00:00Z";
        assertEquals(200, send(path).statusCode());

        ResultQueryCriteria filtered = query.lastCriteria();
        assertEquals(10, filtered.limit());
        assertEquals(Optional.of(PROFILE_ID), filtered.monitoringProfileId());
        assertEquals(Optional.of(SOURCE_ID), filtered.sourceId());
        assertEquals(Optional.of("JOB"), filtered.informationCategory());
        assertEquals(Optional.of(true), filtered.relevant());
        assertEquals(Optional.of("MATCHED"), filtered.classification());
        assertEquals(Optional.of(Instant.parse("2026-09-13T10:00:00Z")), filtered.analyzedFrom());
        assertEquals(Optional.of(Instant.parse("2026-09-13T11:00:00Z")), filtered.analyzedTo());
    }

    /**
     * Return detailed result content, validate lookup parameters, and map missing state to 404.
     */
    @Test
    void shouldServeDetailValidateParametersAndReturnNotFound() throws Exception {
        HttpResponse<String> found = send(
                "/api/v1/results/" + NORMALIZED_ITEM_ID + "?monitoringProfileId=" + PROFILE_ID);
        assertEquals(200, found.statusCode());
        assertTrue(found.body().contains("\"normalizedContent\":\"Java Kafka\""));
        assertTrue(found.body().contains("\"attributes\":{\"location\":\"Remote\"}"));
        assertTrue(found.body().contains("\"traceparent\":null"));

        assertEquals(400, send("/api/v1/results?limit=0").statusCode());
        assertEquals(400, send("/api/v1/results?limit=201").statusCode());
        assertEquals(404, send("/api/v1/results/not-a-hash?monitoringProfileId=" + PROFILE_ID).statusCode());

        String blankProfile = URLEncoder.encode("   ", StandardCharsets.UTF_8);
        assertEquals(400, send(
                "/api/v1/results/" + NORMALIZED_ITEM_ID + "?monitoringProfileId=" + blankProfile).statusCode());

        assertEquals(404, send(
                "/api/v1/results/" + MISSING_NORMALIZED_ITEM_ID + "?monitoringProfileId=" + PROFILE_ID).statusCode());
    }

    private HttpResponse<String> send(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(server.getURI().resolve(path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> serverProperties() {
        return Map.ofEntries(
                Map.entry("spec.name", SPEC_NAME),
                Map.entry("micronaut.server.port", -1),
                Map.entry("micronaut.executors.blocking.virtual", true),
                Map.entry("kafka.enabled", false),
                Map.entry("signalharvester.results.enabled", false));
    }

    private static ResultSummary summary() {
        return new ResultSummary(
                PROFILE_ID,
                NORMALIZED_ITEM_ID,
                SOURCE_ID,
                "JOB",
                Optional.empty(),
                Optional.of("Senior Java Engineer"),
                "https://example.test/jobs/1",
                true,
                "MATCHED",
                90,
                Map.of("location", "Remote"),
                List.of("java", "kafka"),
                "Matched deterministic keywords",
                "keyword-v1",
                Optional.empty(),
                ANALYZED_AT);
    }

    private static ResultDetail detail() {
        return new ResultDetail(
                PROFILE_ID,
                NORMALIZED_ITEM_ID,
                "analysis-event-01",
                "source-event-01",
                "raw-01",
                SOURCE_ID,
                "JOB",
                Optional.empty(),
                Optional.of("Senior Java Engineer"),
                "https://example.test/jobs/1",
                "Java Kafka",
                "text/plain",
                Map.of("location", "Remote"),
                true,
                "MATCHED",
                90,
                List.of("java", "kafka"),
                "Matched deterministic keywords",
                "keyword-v1",
                Optional.empty(),
                ANALYZED_AT,
                "run-01",
                Optional.empty());
    }

    @Singleton
    @Replaces(ResultQueryService.class)
    @Requires(property = "spec.name", value = SPEC_NAME)
    static final class TestResultQuery implements ResultQuery {
        private final AtomicReference<ResultQueryCriteria> lastCriteria = new AtomicReference<>();
        private final AtomicReference<Thread> lastThread = new AtomicReference<>();

        @Override
        public List<ResultSummary> recent(ResultQueryCriteria criteria) {
            lastCriteria.set(criteria);
            lastThread.set(Thread.currentThread());
            return List.of(summary());
        }

        @Override
        public Optional<ResultDetail> find(String monitoringProfileId, String normalizedItemId) {
            lastThread.set(Thread.currentThread());
            return NORMALIZED_ITEM_ID.equals(normalizedItemId) ? Optional.of(detail()) : Optional.empty();
        }

        ResultQueryCriteria lastCriteria() {
            return lastCriteria.get();
        }

        Thread lastThread() {
            return lastThread.get();
        }
    }
}
