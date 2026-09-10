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

class RawItemDiscoveredMapperTest {

    @Test
    void shouldMapFetchedContentWithoutLeakingTransportTypes() {
        UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        RawItemDiscoveredMapper mapper = new RawItemDiscoveredMapper(() -> eventId);
        Instant fetchedAt = Instant.parse("2026-09-10T12:34:56.123456789Z");
        SourceId sourceId = SourceId.of(UUID.fromString("00000000-0000-0000-0000-000000000201"));
        FetchedSourceContent content = new FetchedSourceContent(
                sourceId,
                URI.create("https://example.test/jobs?language=java"),
                200,
                Optional.of("text/plain; charset=ISO-8859-1"),
                "café".getBytes(Charset.forName("ISO-8859-1")),
                fetchedAt);
        RawItemPublicationContext context = new RawItemPublicationContext(
                "raw-42",
                "run-42",
                "profile-7",
                "JOB",
                Optional.of("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));

        RawItemDiscovered event = mapper.map(content, context);

        assertEquals(eventId.toString(), event.getEnvelope().getEventId());
        assertEquals("raw-42", event.getRawItemId());
        assertEquals(RawItemDiscoveredMapper.EVENT_TYPE, event.getEnvelope().getEventType());
        assertEquals("run-42", event.getEnvelope().getCorrelationId());
        assertEquals(RawItemDiscoveredMapper.PRODUCER, event.getEnvelope().getProducer());
        assertEquals(RawItemDiscoveredMapper.SCHEMA_VERSION, event.getEnvelope().getSchemaVersion());
        assertEquals(fetchedAt.getEpochSecond(), event.getEnvelope().getOccurredAt().getSeconds());
        assertEquals(fetchedAt.getNano(), event.getEnvelope().getOccurredAt().getNanos());
        assertEquals(sourceId.value().toString(), event.getSourceId());
        assertEquals("profile-7", event.getMonitoringProfileId());
        assertEquals("JOB", event.getInformationCategory());
        assertEquals(content.requestedUri().toString(), event.getUrl());
        assertEquals("café", event.getContent());
        assertEquals("text/plain; charset=ISO-8859-1", event.getContentType());
        assertFalse(event.hasExternalId());
        assertFalse(event.hasTitle());
        assertFalse(event.hasPublishedAt());
    }

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
