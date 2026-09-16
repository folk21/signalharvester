package io.signalharvester.collection.source.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.collection.source.SourceFetchException;
import io.signalharvester.collection.source.access.OutboundAccessDeniedException;
import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.api.SourceType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link MicronautExternalSourceClient} adaptation from transport responses and failures into
 * collection-owned fetched-content and source-failure semantics.
 *
 * <p>Related specifications: {@code backend-collection-run-orchestration} and feature {@code SECURITY.EXTERNAL_SOURCE_ACCESS}.</p>
 */
class MicronautExternalSourceClientTest {

    private static final Instant FETCH_TIME = Instant.parse("2026-09-09T10:15:30Z");

    /**
     * Map successful response to collection result.
     */
    @Test
    void shouldMapSuccessfulResponseToCollectionResult() {
        ExternalSourceHttpClient httpClient = uri -> HttpResponse.ok("payload".getBytes(StandardCharsets.UTF_8))
                .header("Content-Type", "text/plain");
        MicronautExternalSourceClient client = client(httpClient);
        ConfiguredSource source = source();

        FetchedSourceContent result = client.fetch(source);

        assertEquals(source.id(), result.sourceId());
        assertEquals(source.location(), result.requestedUri());
        assertEquals(200, result.statusCode());
        assertEquals(FETCH_TIME, result.fetchedAt());
        assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8), result.body());
    }

    /**
     * Treat empty successful response body as empty content.
     */
    @Test
    void shouldTreatEmptySuccessfulResponseBodyAsEmptyContent() {
        ExternalSourceHttpClient httpClient = uri -> HttpResponse.<byte[]>noContent();
        MicronautExternalSourceClient client = client(httpClient);

        FetchedSourceContent result = client.fetch(source());

        assertEquals(204, result.statusCode());
        assertEquals(0, result.body().length);
        assertTrue(result.contentType().isEmpty());
    }

    /**
     * Expose error status and retry after without leaking micronaut types.
     */
    @Test
    void shouldExposeErrorStatusAndRetryAfterWithoutLeakingMicronautTypes() {
        ExternalSourceHttpClient httpClient = uri -> HttpResponse.<byte[]>status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "120");
        MicronautExternalSourceClient client = client(httpClient);

        SourceFetchException failure = assertThrows(SourceFetchException.class, () -> client.fetch(source()));

        assertEquals(429, failure.statusCode().orElseThrow());
        assertEquals("120", failure.retryAfter().orElseThrow());
        assertEquals("External source returned HTTP status 429", failure.getMessage());
    }

    /**
     * Normalize response exception for error status.
     */
    @Test
    void shouldNormalizeResponseExceptionForErrorStatus() {
        ExternalSourceHttpClient httpClient = uri -> {
            throw new HttpClientResponseException(
                    "service unavailable", HttpResponse.<byte[]>status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5"));
        };
        MicronautExternalSourceClient client = client(httpClient);

        SourceFetchException failure = assertThrows(SourceFetchException.class, () -> client.fetch(source()));

        assertEquals(503, failure.statusCode().orElseThrow());
        assertEquals("5", failure.retryAfter().orElseThrow());
    }

    /**
     * Normalize transport exception without inventing status code.
     */
    @Test
    void shouldNormalizeTransportExceptionWithoutInventingStatusCode() {
        HttpClientException transportFailure = new HttpClientException("synthetic transport failure");
        ExternalSourceHttpClient httpClient = uri -> {
            throw transportFailure;
        };
        MicronautExternalSourceClient client = client(httpClient);

        SourceFetchException failure = assertThrows(SourceFetchException.class, () -> client.fetch(source()));

        assertFalse(failure.statusCode().isPresent());
        assertTrue(failure.retryAfter().isEmpty());
        assertInstanceOf(HttpClientException.class, failure.getCause());
    }

    /** Map outbound access rejection into the existing source-fetch failure boundary. */
    @Test
    void shouldMapOutboundAccessRejectionToFetchFailure() {
        ExternalSourceHttpClient httpClient = uri -> {
            throw new OutboundAccessDeniedException("blocked destination");
        };
        MicronautExternalSourceClient client = client(httpClient);

        SourceFetchException failure = assertThrows(SourceFetchException.class, () -> client.fetch(source()));

        assertEquals("External source destination blocked by outbound access policy", failure.getMessage());
        assertFalse(failure.statusCode().isPresent());
        assertInstanceOf(OutboundAccessDeniedException.class, failure.getCause());
    }

    private static MicronautExternalSourceClient client(ExternalSourceHttpClient httpClient) {
        return new MicronautExternalSourceClient(httpClient, Clock.fixed(FETCH_TIME, ZoneOffset.UTC));
    }

    private static ConfiguredSource source() {
        return new ConfiguredSource(
                new SourceId(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")),
                "Example",
                SourceType.REST,
                URI.create("https://example.test/data"),
                true,
                Map.of());
    }
}
