package io.signalharvester.collection.event.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.signalharvester.collection.event.RawItemPublicationContext;
import io.signalharvester.collection.source.ExtractedSourceItem;
import io.signalharvester.configuration.api.MonitoringProfileAnalysisSettings;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link RawItemDiscoveredMapper} mapping from collection-owned extracted items to the
 * versioned raw-item event, including optional RSS/Atom metadata.
 *
 * <p>Related specifications: {@code backend-rss-atom-extraction}, {@code backend-event-contracts}.</p>
 */
class RawItemDiscoveredMapperTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID SOURCE_UUID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final String RAW_ITEM_ID = "raw-42";
    private static final String RUN_ID = "run-42";
    private static final String PROFILE_ID = "profile-7";
    private static final String TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final Instant DISCOVERED_AT = Instant.parse("2026-09-10T12:34:56.123456789Z");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-09-10T10:00:00Z");

    /**
     * Preserve semantic item metadata and correlation in the event contract.
     */
    @Test
    void shouldMapExtractedItemMetadata() {
        RawItemDiscoveredMapper mapper = new RawItemDiscoveredMapper(() -> EVENT_ID);
        ExtractedSourceItem item = new ExtractedSourceItem(
                SourceId.of(SOURCE_UUID),
                URI.create("https://example.test/news/42"),
                Optional.of("feed-entry-42"),
                Optional.of("Java 25 released"),
                "Java 25 is now available.",
                "text/plain; charset=UTF-8",
                Optional.of(PUBLISHED_AT),
                DISCOVERED_AT,
                "identity".getBytes(StandardCharsets.UTF_8));
        RawItemPublicationContext context = new RawItemPublicationContext(
                RAW_ITEM_ID,
                RUN_ID,
                PROFILE_ID,
                "TOPIC",
                new MonitoringProfileAnalysisSettings(List.of("java", "kafka"), 1),
                Optional.of(TRACEPARENT));

        RawItemDiscovered event = mapper.map(item, context);

        assertEquals(EVENT_ID.toString(), event.getEnvelope().getEventId());
        assertEquals(RAW_ITEM_ID, event.getRawItemId());
        assertEquals(RUN_ID, event.getEnvelope().getCorrelationId());
        assertEquals(TRACEPARENT, event.getEnvelope().getTraceparent());
        assertEquals(DISCOVERED_AT.getEpochSecond(), event.getEnvelope().getOccurredAt().getSeconds());
        assertEquals(SOURCE_UUID.toString(), event.getSourceId());
        assertEquals(PROFILE_ID, event.getMonitoringProfileId());
        assertEquals("feed-entry-42", event.getExternalId());
        assertEquals("Java 25 released", event.getTitle());
        assertEquals(item.url().toString(), event.getUrl());
        assertEquals(item.content(), event.getContent());
        assertEquals(item.contentType(), event.getContentType());
        assertEquals(PUBLISHED_AT.getEpochSecond(), event.getPublishedAt().getSeconds());
        assertEquals(List.of("java", "kafka"), event.getAnalysisSettings().getKeywordsList());
        assertEquals(1, event.getAnalysisSettings().getMinimumMatches());
        assertTrue(event.hasExternalId());
        assertTrue(event.hasTitle());
        assertTrue(event.hasPublishedAt());
    }

    /**
     * Leave optional fields absent for passthrough-style items.
     */
    @Test
    void shouldLeaveOptionalFieldsAbsent() {
        RawItemDiscoveredMapper mapper = new RawItemDiscoveredMapper(UUID::randomUUID);
        ExtractedSourceItem item = new ExtractedSourceItem(
                SourceId.of(UUID.randomUUID()),
                URI.create("https://example.test/api"),
                Optional.empty(),
                Optional.empty(),
                "hello",
                "application/json",
                Optional.empty(),
                DISCOVERED_AT,
                "hello".getBytes(StandardCharsets.UTF_8));

        RawItemDiscovered event = mapper.map(item, new RawItemPublicationContext(
                "raw-1", "run-1", "profile-1", "TOPIC",
                new MonitoringProfileAnalysisSettings(List.of("java"), 1), Optional.empty()));

        assertFalse(event.hasExternalId());
        assertFalse(event.hasTitle());
        assertFalse(event.hasPublishedAt());
        assertEquals("", event.getEnvelope().getTraceparent());
    }
}
