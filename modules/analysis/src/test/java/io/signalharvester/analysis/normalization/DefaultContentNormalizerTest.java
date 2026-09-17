package io.signalharvester.analysis.normalization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.signalharvester.analysis.model.DiscoveredRawItem;
import io.signalharvester.analysis.model.KeywordAnalysisSettings;
import io.signalharvester.analysis.model.NormalizedContentItem;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Verifies deterministic normalization and logical-item identity produced by {@link DefaultContentNormalizer}
 * across equivalent content, formatting, and provenance variations.
 *
 * <p>Related specification: {@code backend-analysis-normalization-deduplication}.</p>
 */
class DefaultContentNormalizerTest {

    private static final String PROFILE_A = "profile-a";
    private static final String PROFILE_B = "profile-b";
    private static final String SOURCE_EVENT_ID = "raw-event-01";
    private static final String RUN_ID = "run-01";
    private static final String RAW_ITEM_ID = "raw-01";
    private static final String SOURCE_ID = "source-01";
    private static final Instant DISCOVERED_AT = Instant.parse("2026-09-10T18:00:00Z");

    private final DefaultContentNormalizer normalizer =
            new DefaultContentNormalizer(new NormalizedItemIdentityFactory());

    /**
     * Normalize whitespace URL and fallback identity deterministically.
     */
    @Test
    void shouldNormalizeWhitespaceUrlAndFallbackIdentityDeterministically() {
        NormalizedContentItem first = normalizer.normalize(raw(
                PROFILE_A,
                Optional.empty(),
                URI.create("HTTPS://Example.TEST:443/jobs/../jobs/42"),
                "  Java   backend\nKafka  "));
        NormalizedContentItem second = normalizer.normalize(raw(
                PROFILE_B,
                Optional.empty(),
                URI.create("https://example.test/jobs/42"),
                "Java backend Kafka"));

        assertEquals("https://example.test/jobs/42", first.url().toString());
        assertEquals("Java backend Kafka", first.content());
        assertEquals(first.normalizedItemId(), second.normalizedItemId());
    }

    /**
     * Preserve existing percent encoding during URL normalization.
     */
    @Test
    void shouldPreserveExistingPercentEncodingDuringUrlNormalization() {
        NormalizedContentItem normalized = normalizer.normalize(raw(
                PROFILE_A,
                Optional.empty(),
                URI.create("HTTPS://Example.TEST:443/jobs/a%2Fb?q=java%20backend"),
                "Java"));

        assertEquals("https://example.test/jobs/a%2Fb?q=java%20backend", normalized.url().toString());
    }

    /**
     * Reject non-HTTP raw item URL before normalization.
     */
    @Test
    void shouldRejectNonHttpRawItemUrlBeforeNormalization() {
        assertThrows(IllegalArgumentException.class, () -> raw(
                PROFILE_A, Optional.empty(), URI.create("file:///tmp/jobs"), "Java"));
    }

    /**
     * Prefer stable external identity over content fingerprint.
     */
    @Test
    void shouldPreferStableExternalIdentityOverContentFingerprint() {
        NormalizedContentItem first = normalizer.normalize(raw(
                PROFILE_A,
                Optional.of(" external-42 "),
                URI.create("https://example.test/jobs/42"),
                "Original content"));
        NormalizedContentItem changed = normalizer.normalize(raw(
                PROFILE_A,
                Optional.of("external-42"),
                URI.create("https://example.test/jobs/42?revision=2"),
                "Changed content"));
        NormalizedContentItem otherExternalId = normalizer.normalize(raw(
                PROFILE_A,
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
                SOURCE_EVENT_ID,
                RUN_ID,
                Optional.empty(),
                DISCOVERED_AT,
                RAW_ITEM_ID,
                SOURCE_ID,
                profileId,
                "JOB",
                new KeywordAnalysisSettings(List.of("java"), 1),
                externalId,
                Optional.of("  Senior   Java Engineer "),
                url,
                content,
                "text/plain; charset=UTF-8",
                Optional.empty());
    }
}
