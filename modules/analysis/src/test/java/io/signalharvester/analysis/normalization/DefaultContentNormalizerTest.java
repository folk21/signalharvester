package io.signalharvester.analysis.normalization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.signalharvester.analysis.model.DiscoveredRawItem;
import io.signalharvester.analysis.model.NormalizedContentItem;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefaultContentNormalizerTest {

    private final DefaultContentNormalizer normalizer =
            new DefaultContentNormalizer(new NormalizedItemIdentityFactory());

    @Test
    void shouldNormalizeWhitespaceUrlAndFallbackIdentityDeterministically() {
        NormalizedContentItem first = normalizer.normalize(raw(
                "profile-a",
                Optional.empty(),
                URI.create("HTTPS://Example.TEST:443/jobs/../jobs/42"),
                "  Java   backend\nKafka  "));
        NormalizedContentItem second = normalizer.normalize(raw(
                "profile-b",
                Optional.empty(),
                URI.create("https://example.test/jobs/42"),
                "Java backend Kafka"));

        assertEquals("https://example.test/jobs/42", first.url().toString());
        assertEquals("Java backend Kafka", first.content());
        assertEquals(first.normalizedItemId(), second.normalizedItemId());
    }

    @Test
    void shouldPreserveExistingPercentEncodingDuringUrlNormalization() {
        NormalizedContentItem normalized = normalizer.normalize(raw(
                "profile-a",
                Optional.empty(),
                URI.create("HTTPS://Example.TEST:443/jobs/a%2Fb?q=java%20backend"),
                "Java"));

        assertEquals("https://example.test/jobs/a%2Fb?q=java%20backend", normalized.url().toString());
    }

    @Test
    void shouldRejectNonHttpRawItemUrlBeforeNormalization() {
        assertThrows(IllegalArgumentException.class, () -> raw(
                "profile-a", Optional.empty(), URI.create("file:///tmp/jobs"), "Java"));
    }

    @Test
    void shouldPreferStableExternalIdentityOverContentFingerprint() {
        NormalizedContentItem first = normalizer.normalize(raw(
                "profile-a",
                Optional.of(" external-42 "),
                URI.create("https://example.test/jobs/42"),
                "Original content"));
        NormalizedContentItem changed = normalizer.normalize(raw(
                "profile-a",
                Optional.of("external-42"),
                URI.create("https://example.test/jobs/42?revision=2"),
                "Changed content"));
        NormalizedContentItem otherExternalId = normalizer.normalize(raw(
                "profile-a",
                Optional.of("external-43"),
                URI.create("https://example.test/jobs/42"),
                "Original content"));

        assertEquals(first.normalizedItemId(), changed.normalizedItemId());
        assertNotEquals(first.normalizedItemId(), otherExternalId.normalizedItemId());
    }

    private static DiscoveredRawItem raw(
            String profileId,
            Optional<String> externalId,
            URI url,
            String content) {
        return new DiscoveredRawItem(
                "raw-event-01",
                "run-01",
                Optional.empty(),
                Instant.parse("2026-09-10T18:00:00Z"),
                "raw-01",
                "source-01",
                profileId,
                "JOB",
                externalId,
                Optional.of("  Senior   Java Engineer "),
                url,
                content,
                "text/plain; charset=UTF-8",
                Optional.empty());
    }
}
