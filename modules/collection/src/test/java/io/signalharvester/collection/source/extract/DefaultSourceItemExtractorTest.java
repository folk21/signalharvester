package io.signalharvester.collection.source.extract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.signalharvester.collection.source.ExtractedSourceItem;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.api.SourceType;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link DefaultSourceItemExtractor} routing and backward-compatible one-item passthrough
 * behavior for non-feed source types.
 *
 * <p>Related specification: {@code backend-rss-atom-extraction}.</p>
 */
class DefaultSourceItemExtractorTest {

    private static final SourceId SOURCE_ID = SourceId.of(
            UUID.fromString("00000000-0000-0000-0000-000000000882"));
    private static final URI SOURCE_URI = URI.create("https://example.test/api");
    private static final Instant FETCHED_AT = Instant.parse("2026-09-13T12:00:00Z");

    /**
     * Preserve original response bytes as passthrough raw identity material.
     */
    @Test
    void shouldPreservePassthroughIdentityMaterial() {
        byte[] body = "café".getBytes(Charset.forName("ISO-8859-1"));
        FetchedSourceContent fetched = new FetchedSourceContent(
                SOURCE_ID,
                SOURCE_URI,
                200,
                Optional.of("text/plain; charset=ISO-8859-1"),
                body,
                FETCHED_AT);
        ConfiguredSource source = new ConfiguredSource(
                SOURCE_ID, "REST", SourceType.REST, SOURCE_URI, true, Map.of());

        List<ExtractedSourceItem> items = extractor().extract(source, fetched);

        assertEquals(1, items.size());
        assertEquals("café", items.getFirst().content());
        assertEquals(SOURCE_URI, items.getFirst().url());
        assertArrayEquals(body, items.getFirst().identityPayload());
        assertTrue(items.getFirst().externalId().isEmpty());
    }


    /**
     * Surface an invalid declared charset as an extraction failure.
     */
    @Test
    void shouldRejectInvalidDeclaredCharset() {
        FetchedSourceContent fetched = new FetchedSourceContent(
                SOURCE_ID,
                SOURCE_URI,
                200,
                Optional.of("text/plain; charset=not-a-real-charset"),
                "payload".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                FETCHED_AT);
        ConfiguredSource source = new ConfiguredSource(
                SOURCE_ID, "REST", SourceType.REST, SOURCE_URI, true, Map.of());

        assertThrows(SourceItemExtractionException.class, () -> extractor().extract(source, fetched));
    }

    private static DefaultSourceItemExtractor extractor() {
        return new DefaultSourceItemExtractor(new RssAtomItemExtractor(() -> 500));
    }
}
