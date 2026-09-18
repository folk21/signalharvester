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
 * Verifies configuration-driven HTML extraction through persisted CSS selectors and attributes.
 *
 * <p>Feature: {@code COLLECTION.ADAPTERS}.</p>
 */
class HtmlSourceItemExtractorTest {

    private static final SourceId SOURCE_ID = SourceId.of(
            UUID.fromString("00000000-0000-0000-0000-000000000884"));
    private static final URI SOURCE_URI = URI.create("https://example.test/jobs");
    private static final Instant FETCHED_AT = Instant.parse("2026-09-14T08:00:00Z");

    /** Extract repeated HTML candidates with selector-based metadata and relative URLs. */
    @Test
    void shouldExtractConfiguredHtmlItems() {
        ConfiguredSource source = source(Map.ofEntries(
                Map.entry("html.itemSelector", "article.job"),
                Map.entry("html.externalIdAttribute", "data-id"),
                Map.entry("html.titleSelector", "h2"),
                Map.entry("html.urlSelector", "a.details"),
                Map.entry("html.urlAttribute", "href"),
                Map.entry("html.contentSelector", ".description"),
                Map.entry("html.publishedAtSelector", "time"),
                Map.entry("html.publishedAtAttribute", "datetime")));
        String html = """
                <html><body>
                  <article class="job" data-id="job-42">
                    <h2>Senior Java Engineer</h2>
                    <a class="details" href="/jobs/42">Details</a>
                    <div class="description">Java <strong>Kafka</strong> PostgreSQL</div>
                    <time datetime="2026-09-14T07:20:00Z">today</time>
                  </article>
                  <article class="job" data-id="job-43">
                    <h2>Backend Engineer</h2>
                    <a class="details" href="https://jobs.example.test/43">Details</a>
                    <div class="description">Distributed systems</div>
                  </article>
                </body></html>
                """;

        List<ExtractedSourceItem> items = extractor(10).extract(source, fetched(html));

        assertEquals(2, items.size());
        ExtractedSourceItem first = items.getFirst();
        assertEquals("job-42", first.externalId().orElseThrow());
        assertEquals("Senior Java Engineer", first.title().orElseThrow());
        assertEquals(URI.create("https://example.test/jobs/42"), first.url());
        assertEquals("Java Kafka PostgreSQL", first.content());
        assertEquals(Instant.parse("2026-09-14T07:20:00Z"), first.publishedAt().orElseThrow());
        assertEquals(FETCHED_AT, first.discoveredAt());
    }

    /** Default content extraction to the whole candidate element when contentSelector is absent. */
    @Test
    void shouldUseCandidateTextAsDefaultContent() {
        ConfiguredSource source = source(Map.of("html.itemSelector", "li.item"));

        ExtractedSourceItem item = extractor(10).extract(
                source,
                fetched("<ul><li class='item'>Java <b>backend</b></li></ul>"))
                .getFirst();

        assertEquals("Java backend", item.content());
        assertEquals(SOURCE_URI, item.url());
    }

    /** Reject HTML candidate sets that exceed the generic extraction item bound. */
    @Test
    void shouldRejectTooManyCandidateItems() {
        ConfiguredSource source = source(Map.of("html.itemSelector", "li"));

        SourceItemExtractionException failure = assertThrows(
                SourceItemExtractionException.class,
                () -> extractor(1).extract(source, fetched("<ul><li>one</li><li>two</li></ul>")));

        assertTrue(failure.getMessage().contains("exceeding configured maximum 1"));
    }

    /** Reject malformed CSS selector configuration explicitly. */
    @Test
    void shouldRejectInvalidSelector() {
        ConfiguredSource source = source(Map.of("html.itemSelector", "article["));

        assertThrows(SourceItemExtractionException.class, () -> extractor(10).extract(
                source,
                fetched("<article>one</article>")));
    }

    private static HtmlSourceItemExtractor extractor(int maxItems) {
        return new HtmlSourceItemExtractor(() -> maxItems);
    }

    private static ConfiguredSource source(Map<String, String> settings) {
        return new ConfiguredSource(SOURCE_ID, "HTML", SourceType.HTML, SOURCE_URI, true, settings);
    }

    private static FetchedSourceContent fetched(String body) {
        return new FetchedSourceContent(
                SOURCE_ID,
                SOURCE_URI,
                200,
                Optional.of("text/html; charset=UTF-8"),
                body.getBytes(StandardCharsets.UTF_8),
                FETCHED_AT);
    }
}
