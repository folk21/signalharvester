package io.signalharvester.collection.event.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.signalharvester.collection.event.RawItemPublicationContext;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link RawItemDiscoveredMapper} mapping from fetched source content and publication context to the
 * versioned {@code RawItemDiscovered} event contract.
 *
 * <p>Related specifications: {@code backend-collection-run-orchestration}, {@code backend-event-contracts}.</p>
 */
class RawItemDiscoveredMapperTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID SOURCE_UUID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final String RAW_ITEM_ID = "raw-42";
    private static final String RUN_ID = "run-42";
    private static final String PROFILE_ID = "profile-7";
    private static final String TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    /**
     * Map fetched content without leaking transport types.
     */
    @Test
    void shouldMapFetchedContentWithoutLeakingTransportTypes() {
        RawItemDiscoveredMapper mapper = new RawItemDiscoveredMapper(() -> EVENT_ID);
        Instant fetchedAt = Instant.parse("2026-09-10T12:34:56.123456789Z");
        SourceId sourceId = SourceId.of(SOURCE_UUID);
        FetchedSourceContent content = new FetchedSourceContent(
                sourceId,
                URI.create("https://example.test/jobs?language=java"),
                200,
                Optional.of("text/plain; charset=ISO-8859-1"),
                "café".getBytes(Charset.forName("ISO-8859-1")),
                fetchedAt);
        RawItemPublicationContext context = new RawItemPublicationContext(
                RAW_ITEM_ID,
                RUN_ID,
                PROFILE_ID,
                "JOB",
                Optional.of(TRACEPARENT));

        RawItemDiscovered event = mapper.map(content, context);

        assertEquals(EVENT_ID.toString(), event.getEnvelope().getEventId());
        assertEquals(RAW_ITEM_ID, event.getRawItemId());
        assertEquals(RawItemDiscoveredMapper.EVENT_TYPE, event.getEnvelope().getEventType());
        assertEquals(RUN_ID, event.getEnvelope().getCorrelationId());
        assertEquals(RawItemDiscoveredMapper.PRODUCER, event.getEnvelope().getProducer());
        assertEquals(RawItemDiscoveredMapper.SCHEMA_VERSION, event.getEnvelope().getSchemaVersion());
        assertEquals(fetchedAt.getEpochSecond(), event.getEnvelope().getOccurredAt().getSeconds());
        assertEquals(fetchedAt.getNano(), event.getEnvelope().getOccurredAt().getNanos());
        assertEquals(sourceId.value().toString(), event.getSourceId());
        assertEquals(PROFILE_ID, event.getMonitoringProfileId());
        assertEquals("JOB", event.getInformationCategory());
        assertEquals(content.requestedUri().toString(), event.getUrl());
        assertEquals("café", event.getContent());
        assertEquals("text/plain; charset=ISO-8859-1", event.getContentType());
        assertFalse(event.hasExternalId());
        assertFalse(event.hasTitle());
        assertFalse(event.hasPublishedAt());
    }

    /**
     * Default unknown response charset to UTF-8.
     */
    @Test
    void shouldDefaultUnknownResponseCharsetToUtf8() {
        RawItemDiscoveredMapper mapper = new RawItemDiscoveredMapper(UUID::randomUUID);
        FetchedSourceContent content = new FetchedSourceContent(
                SourceId.of(UUID.randomUUID()),
                URI.create("https://example.test/feed"),
                200,
                Optional.empty(),
                "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Instant.parse("2026-09-10T12:00:00Z"));

        RawItemDiscovered event = mapper.map(content, new RawItemPublicationContext(
                "raw-1", "run-1", "profile-1", "TOPIC", Optional.empty()));

        assertEquals("hello", event.getContent());
        assertEquals("application/octet-stream", event.getContentType());
        assertEquals("", event.getEnvelope().getTraceparent());
    }
}
