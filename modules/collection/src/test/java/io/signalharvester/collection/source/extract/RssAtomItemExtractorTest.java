package io.signalharvester.collection.source.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link RssAtomItemExtractor} parsing, secure bounds, and semantic metadata extraction for
 * representative RSS 2.0 and Atom feeds.
 *
 * <p>Related specification: {@code backend-rss-atom-extraction}.</p>
 *
 * <p>Feature: {@code COLLECTION.ADAPTERS}.</p>
 */
class RssAtomItemExtractorTest {

    private static final SourceId SOURCE_ID = SourceId.of(
            UUID.fromString("00000000-0000-0000-0000-000000000881"));
    private static final URI FEED_URI = URI.create("https://example.test/feed.xml");
    private static final Instant FETCHED_AT = Instant.parse("2026-09-13T12:00:00Z");

    /**
     * Extract RSS entries with identity, title, URL, content, and RFC-1123 publication time.
     */
    @Test
    void shouldExtractRssItems() {
        String xml = """
                <rss version="2.0"><channel>
                  <item>
                    <guid>news-42</guid>
                    <title> Java 25   released </title>
                    <link>https://example.test/news/42</link>
                    <description><![CDATA[<p>Java 25 is <strong>available</strong>.</p>]]></description>
                    <pubDate>Sun, 13 Sep 2026 10:30:00 GMT</pubDate>
                  </item>
                </channel></rss>
                """;

        List<ExtractedSourceItem> items = extractor(10).extract(source(), fetched(xml));

        assertEquals(1, items.size());
        ExtractedSourceItem item = items.getFirst();
        assertEquals("news-42", item.externalId().orElseThrow());
        assertEquals("Java 25 released", item.title().orElseThrow());
        assertEquals(URI.create("https://example.test/news/42"), item.url());
        assertEquals("Java 25 is available .", item.content());
        assertEquals(Instant.parse("2026-09-13T10:30:00Z"), item.publishedAt().orElseThrow());
        assertEquals(FETCHED_AT, item.discoveredAt());
        assertTrue(item.identityPayload().length > 0);
    }

    /**
     * Extract Atom entries with relative alternate links and ISO publication time.
     */
    @Test
    void shouldExtractAtomEntries() {
        String xml = """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <id>tag:example.test,2026:entry-7</id>
                    <title>Backend update</title>
                    <link rel="alternate" href="/articles/7"/>
                    <summary>Kafka and PostgreSQL</summary>
                    <published>2026-09-13T09:15:00Z</published>
                  </entry>
                </feed>
                """;

        ExtractedSourceItem item = extractor(10).extract(source(), fetched(xml)).getFirst();

        assertEquals("tag:example.test,2026:entry-7", item.externalId().orElseThrow());
        assertEquals(URI.create("https://example.test/articles/7"), item.url());
        assertEquals("Kafka and PostgreSQL", item.content());
        assertEquals(Instant.parse("2026-09-13T09:15:00Z"), item.publishedAt().orElseThrow());
    }

    /**
     * Keep feed entries without explicit IDs or links distinct without inventing a shared external ID.
     */
    @Test
    void shouldNotInventSharedExternalIdForEntriesWithoutIdentity() {
        String xml = """
                <rss version="2.0"><channel>
                  <item><title>First item</title><description>First body</description></item>
                  <item><title>Second item</title><description>Second body</description></item>
                </channel></rss>
                """;

        List<ExtractedSourceItem> items = extractor(10).extract(source(), fetched(xml));

        assertEquals(2, items.size());
        assertTrue(items.get(0).externalId().isEmpty());
        assertTrue(items.get(1).externalId().isEmpty());
        assertEquals(FEED_URI, items.get(0).url());
        assertEquals(FEED_URI, items.get(1).url());
        assertFalse(Arrays.equals(items.get(0).identityPayload(), items.get(1).identityPayload()));
    }

    /**
     * Return no semantic items for a valid empty feed.
     */
    @Test
    void shouldReturnEmptyListForValidEmptyFeed() {
        List<ExtractedSourceItem> items = extractor(10).extract(
                source(), fetched("<rss version=\"2.0\"><channel/></rss>"));

        assertTrue(items.isEmpty());
    }

    /**
     * Reject malformed XML as an extraction failure.
     */
    @Test
    void shouldRejectMalformedXml() {
        assertThrows(
                SourceItemExtractionException.class,
                () -> extractor(10).extract(source(), fetched("<rss><channel><item>")));
    }

    /**
     * Reject feeds whose item count exceeds the configured bounded extraction limit.
     */
    @Test
    void shouldRejectFeedBeyondConfiguredItemLimit() {
        String xml = """
                <rss version="2.0"><channel>
                  <item><guid>1</guid><title>One</title></item>
                  <item><guid>2</guid><title>Two</title></item>
                </channel></rss>
                """;

        SourceItemExtractionException failure = assertThrows(
                SourceItemExtractionException.class,
                () -> extractor(1).extract(source(), fetched(xml)));

        assertTrue(failure.getMessage().contains("exceeding configured maximum 1"));
    }

    /**
     * Reject XML documents that attempt to declare a DTD.
     */
    @Test
    void shouldRejectDoctype() {
        String xml = """
                <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <rss version="2.0"><channel><item><title>&xxe;</title></item></channel></rss>
                """;

        assertThrows(
                SourceItemExtractionException.class,
                () -> extractor(10).extract(source(), fetched(xml)));
    }

    private static RssAtomItemExtractor extractor(int maxItems) {
        return new RssAtomItemExtractor(() -> maxItems);
    }

    private static ConfiguredSource source() {
        return new ConfiguredSource(SOURCE_ID, "Feed", SourceType.RSS, FEED_URI, true, Map.of());
    }

    private static FetchedSourceContent fetched(String xml) {
        return new FetchedSourceContent(
                SOURCE_ID,
                FEED_URI,
                200,
                Optional.of("application/rss+xml; charset=UTF-8"),
                xml.getBytes(StandardCharsets.UTF_8),
                FETCHED_AT);
    }
}
