package io.signalharvester.collection.source.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.collection.source.SourceFetchException;
import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.api.SourceType;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the JDK HTTP adapter against a deterministic loopback source.
 */
class JdkHttpExternalSourceClientTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-09-09T08:00:00Z");

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldReturnBoundedResponseWithTransportMetadata() throws IOException {
        byte[] responseBody = "{\"items\":[{\"id\":\"job-1\"}]}".getBytes(StandardCharsets.UTF_8);
        URI sourceUri = startServer(200, "application/json", responseBody);
        ConfiguredSource source = source(sourceUri);
        JdkHttpExternalSourceClient client = client(1024);

        FetchedSourceContent result = client.fetch(source);

        assertEquals(source.id(), result.sourceId());
        assertEquals(sourceUri, result.requestedUri());
        assertEquals(200, result.statusCode());
        assertEquals("application/json", result.contentType().orElseThrow());
        assertArrayEquals(responseBody, result.body());
        assertEquals(FETCHED_AT, result.fetchedAt());
    }

    @Test
    void shouldRejectNonSuccessfulResponse() throws IOException {
        URI sourceUri = startServer(503, "text/plain", "unavailable".getBytes(StandardCharsets.UTF_8));
        ConfiguredSource source = source(sourceUri);

        SourceFetchException exception = assertThrows(SourceFetchException.class, () -> client(1024).fetch(source));

        assertEquals(source.id(), exception.sourceId());
        assertEquals(sourceUri, exception.uri());
        assertEquals("Source returned HTTP status 503", exception.getMessage());
    }

    @Test
    void shouldRejectResponseAboveConfiguredLimit() throws IOException {
        URI sourceUri = startServer(200, "text/plain", "123456789".getBytes(StandardCharsets.UTF_8));
        ConfiguredSource source = source(sourceUri);

        SourceFetchException exception = assertThrows(SourceFetchException.class, () -> client(8).fetch(source));

        assertEquals("Source response exceeds 8 bytes", exception.getMessage());
    }

    private JdkHttpExternalSourceClient client(int maxResponseBytes) {
        return new JdkHttpExternalSourceClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                Clock.fixed(FETCHED_AT, ZoneOffset.UTC),
                Duration.ofSeconds(1),
                maxResponseBytes);
    }

    private ConfiguredSource source(URI uri) {
        return new ConfiguredSource(
                SourceId.of(UUID.fromString("6bb70a8d-4077-44b4-a4bb-248bcd54be40")),
                "Deterministic REST source",
                SourceType.REST,
                uri,
                true,
                Map.of());
    }

    private URI startServer(int statusCode, String contentType, byte[] body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/source", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(statusCode, body.length);
            try (var responseBody = exchange.getResponseBody()) {
                responseBody.write(body);
            }
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/source");
    }
}
