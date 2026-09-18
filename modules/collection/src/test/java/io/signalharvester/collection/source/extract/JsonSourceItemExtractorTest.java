package io.signalharvester.collection.source.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.signalharvester.collection.source.ExtractedSourceItem;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.api.SourceType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies configuration-driven REST JSON extraction through RFC 6901 pointers and item bounds.
 *
 * <p>Feature: {@code COLLECTION.ADAPTERS}.</p>
 */
class JsonSourceItemExtractorTest {

    private static final SourceId SOURCE_ID = SourceId.of(
            UUID.fromString("00000000-0000-0000-0000-000000000883"));
    private static final URI SOURCE_URI = URI.create("https://example.test/api/jobs");
    private static final Instant FETCHED_AT = Instant.parse("2026-09-14T08:00:00Z");

    /** Extract an array of JSON objects into semantic items with relative URLs and metadata. */
    @Test
    void shouldExtractConfiguredJsonItems() {
        ConfiguredSource source = source(Map.of(
                "json.itemsPointer", "/jobs",
                "json.externalIdPointer", "/id",
                "json.titlePointer", "/title",
                "json.urlPointer", "/url",
                "json.contentPointer", "/description",
                "json.publishedAtPointer", "/publishedAt"));
        String body = """
                {
                  "jobs": [
                    {
                      "id": "job-17",
                      "title": "Senior Java Engineer",
                      "url": "/jobs/17",
                      "description": "Java, Kafka and PostgreSQL",
                      "publishedAt": "2026-09-14T07:30:00Z"
                    },
                    {
                      "id": "job-18",
                      "title": "Backend Engineer",
                      "url": "https://jobs.example.test/18",
                      "description": "Distributed systems"
                    }
                  ]
                }
                """;

        List<ExtractedSourceItem> items = extractor(10).extract(source, fetched(body));

        assertEquals(2, items.size());
        ExtractedSourceItem first = items.getFirst();
        assertEquals("job-17", first.externalId().orElseThrow());
        assertEquals("Senior Java Engineer", first.title().orElseThrow());
        assertEquals(URI.create("https://example.test/jobs/17"), first.url());
        assertEquals("Java, Kafka and PostgreSQL", first.content());
        assertEquals("text/plain; charset=UTF-8", first.contentType());
        assertEquals(Instant.parse("2026-09-14T07:30:00Z"), first.publishedAt().orElseThrow());
        assertEquals(FETCHED_AT, first.discoveredAt());
        assertTrue(first.identityPayload().length > 0);
    }

    /** Allow one object to be the candidate item and preserve structured content as JSON. */
    @Test
    void shouldExtractSingleObjectAndStructuredContent() {
        ConfiguredSource source = source(Map.of("json.contentPointer", "/payload"));

        ExtractedSourceItem item = extractor(10).extract(
                source,
                fetched("{\"payload\":{\"language\":\"java\",\"level\":\"senior\"}}"))
                .getFirst();

        assertEquals("{\"language\":\"java\",\"level\":\"senior\"}", item.content());
        assertEquals("application/json", item.contentType());
        assertEquals(SOURCE_URI, item.url());
    }

    /** Canonicalize structured JSON content so object field order does not change semantic identity. */
    @Test
    void shouldCanonicalizeStructuredContentForStableIdentity() {
        ConfiguredSource source = source(Map.of("json.contentPointer", "/payload"));

        ExtractedSourceItem first = extractor(10).extract(
                source,
                fetched("{\"payload\":{\"z\":1,\"nested\":{\"b\":2,\"a\":1}}}"))
                .getFirst();
        ExtractedSourceItem second = extractor(10).extract(
                source,
                fetched("{\"payload\":{\"nested\":{\"a\":1,\"b\":2},\"z\":1}}"))
                .getFirst();

        assertEquals("{\"nested\":{\"a\":1,\"b\":2},\"z\":1}", first.content());
        assertEquals(first.content(), second.content());
        assertTrue(java.util.Arrays.equals(first.identityPayload(), second.identityPayload()));
    }

    /** Reject a configured JSON response that exceeds the generic extraction item bound. */
    @Test
    void shouldRejectTooManyCandidateItems() {
        ConfiguredSource source = source(Map.of(
                "json.itemsPointer", "/items",
                "json.contentPointer", "/content"));

        SourceItemExtractionException failure = assertThrows(
                SourceItemExtractionException.class,
                () -> extractor(1).extract(
                        source,
                        fetched("{\"items\":[{\"content\":\"one\"},{\"content\":\"two\"}]}")));

        assertTrue(failure.getMessage().contains("exceeding configured maximum 1"));
    }

    /** Reject malformed pointer configuration instead of silently falling back to passthrough. */
    @Test
    void shouldRejectInvalidPointerConfiguration() {
        ConfiguredSource source = source(Map.of(
                "json.itemsPointer", "items",
                "json.contentPointer", "/content"));

        assertThrows(SourceItemExtractionException.class, () -> extractor(10).extract(
                source,
                fetched("{\"items\":[{\"content\":\"one\"}]}")));
    }

    /** Require content mapping whenever any json.* setting enables configured extraction. */
    @Test
    void shouldRequireContentPointer() {
        ConfiguredSource source = source(Map.of("json.itemsPointer", "/items"));

        SourceItemExtractionException failure = assertThrows(
                SourceItemExtractionException.class,
                () -> extractor(10).extract(source, fetched("{\"items\":[]}")));

        assertTrue(failure.getMessage().contains("json.contentPointer"));
    }

    private static JsonSourceItemExtractor extractor(int maxItems) {
        return new JsonSourceItemExtractor(() -> maxItems);
    }

    private static ConfiguredSource source(Map<String, String> settings) {
        return new ConfiguredSource(SOURCE_ID, "REST JSON", SourceType.REST, SOURCE_URI, true, settings);
    }

    private static FetchedSourceContent fetched(String body) {
        return new FetchedSourceContent(
                SOURCE_ID,
                SOURCE_URI,
                200,
                Optional.of("application/json; charset=UTF-8"),
                body.getBytes(StandardCharsets.UTF_8),
                FETCHED_AT);
    }
}
