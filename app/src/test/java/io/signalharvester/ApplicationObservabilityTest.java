package io.signalharvester;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Verifies application health probes and Prometheus exposure for feature {@code OBSERVABILITY.APPLICATION}. */
class ApplicationObservabilityTest {

    private EmbeddedServer server;

    /** Expose liveness, readiness, and Prometheus endpoints without requiring external infrastructure. */
    @Test
    void shouldExposeOperationalObservabilityEndpoints() throws Exception {
        server = ApplicationContext.run(EmbeddedServer.class, Map.ofEntries(
                Map.entry("micronaut.server.port", -1),
                Map.entry("datasources.default.enabled", false),
                Map.entry("flyway.datasources.default.enabled", false),
                Map.entry("kafka.enabled", false),
                Map.entry("signalharvester.collection.scheduler.enabled", false),
                Map.entry("signalharvester.analysis.enabled", false),
                Map.entry("signalharvester.results.enabled", false),
                Map.entry("signalharvester.event-observation.enabled", false),
                Map.entry("otel.traces.exporter", "none")), "test");

        server.getApplicationContext()
                .getBean(MeterRegistry.class)
                .counter("signalharvester.test.observability")
                .increment();

        HttpResponse<String> health = get("/health");
        HttpResponse<String> liveness = get("/health/liveness");
        HttpResponse<String> readiness = get("/health/readiness");
        HttpResponse<String> prometheus = get("/prometheus");

        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("UP"));
        assertEquals(200, liveness.statusCode());
        assertTrue(liveness.body().contains("UP"));
        assertEquals(200, readiness.statusCode());
        assertTrue(readiness.body().contains("UP"));
        assertEquals(200, prometheus.statusCode());
        assertTrue(prometheus.body().contains("signalharvester_test_observability"));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.getURL() + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
