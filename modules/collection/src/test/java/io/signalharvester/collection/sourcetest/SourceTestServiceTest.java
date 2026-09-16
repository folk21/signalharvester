package io.signalharvester.collection.sourcetest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.signalharvester.collection.source.ExtractedSourceItem;
import io.signalharvester.collection.source.ExternalSourceClient;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.collection.source.SourceFetchException;
import io.signalharvester.collection.source.extract.SourceItemExtractionException;
import io.signalharvester.collection.source.extract.SourceItemExtractor;
import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceConfigurationProvider;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.api.SourceType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Verifies diagnostic source testing reuses fetch/extraction ports and keeps previews bounded. */
class SourceTestServiceTest {

    private static final SourceId SOURCE_ID = SourceId.of(
            UUID.fromString("00000000-0000-0000-0000-000000000885"));
    private static final URI SOURCE_URI = URI.create("https://example.test/source");
    private static final Instant FETCHED_AT = Instant.parse("2026-09-14T08:00:00Z");

    /** Test a disabled persisted source and return bounded extracted-item diagnostics. */
    @Test
    void shouldTestDisabledSourceAndBoundPreview() {
        ConfiguredSource source = source(false);
        AtomicBoolean fetched = new AtomicBoolean();
        ExternalSourceClient client = configuredSource -> {
            fetched.set(true);
            assertFalse(configuredSource.enabled());
            return fetchedContent();
        };
        SourceItemExtractor extractor = (configuredSource, content) -> List.of(
                item("first", "abcdefgh"),
                item("second", "ijklmnop"));
        SourceTestService service = new SourceTestService(
                provider(Optional.of(source)), client, extractor, configuration(1, 4));

        SourceTestResult result = service.test(SOURCE_ID);

        assertTrue(fetched.get());
        assertEquals(SourceTestStatus.SUCCEEDED, result.status());
        assertEquals(200, result.httpStatus().orElseThrow());
        assertEquals("application/json", result.responseContentType().orElseThrow());
        assertEquals(2, result.candidateItemCount());
        assertEquals(1, result.preview().size());
        assertEquals("abcd", result.preview().getFirst().contentPreview());
        assertTrue(result.preview().getFirst().contentTruncated());
        assertTrue(result.failureMessage().isEmpty());
    }

    /** Return an HTTP fetch failure as a diagnostic result instead of throwing through the REST boundary. */
    @Test
    void shouldReturnFetchFailureDiagnostic() {
        ExternalSourceClient client = configuredSource -> {
            throw new SourceFetchException(configuredSource.id(), configuredSource.location(), 429, Optional.of("60"));
        };
        SourceTestService service = new SourceTestService(
                provider(Optional.of(source(true))), client, (configuredSource, content) -> List.of(), configuration(5, 500));

        SourceTestResult result = service.test(SOURCE_ID);

        assertEquals(SourceTestStatus.FETCH_FAILED, result.status());
        assertEquals(429, result.httpStatus().orElseThrow());
        assertEquals(0, result.candidateItemCount());
        assertTrue(result.preview().isEmpty());
        assertTrue(result.failureMessage().orElseThrow().contains("429"));
    }

    /** Surface outbound access rejection as a bounded source-test fetch failure. */
    @Test
    void shouldReturnOutboundAccessPolicyFailureDiagnostic() {
        ExternalSourceClient client = configuredSource -> {
            throw new SourceFetchException(
                    configuredSource.id(),
                    configuredSource.location(),
                    "External source destination blocked by outbound access policy");
        };
        SourceTestService service = new SourceTestService(
                provider(Optional.of(source(true))), client, (configuredSource, content) -> List.of(), configuration(5, 500));

        SourceTestResult result = service.test(SOURCE_ID);

        assertEquals(SourceTestStatus.FETCH_FAILED, result.status());
        assertTrue(result.httpStatus().isEmpty());
        assertEquals(0, result.candidateItemCount());
        assertTrue(result.failureMessage().orElseThrow().contains("blocked by outbound access policy"));
    }

    /** Preserve successful HTTP metadata when extraction configuration fails. */
    @Test
    void shouldReturnExtractionFailureDiagnostic() {
        SourceItemExtractor extractor = (configuredSource, content) -> {
            throw new SourceItemExtractionException(configuredSource.id(), "invalid selector");
        };
        SourceTestService service = new SourceTestService(
                provider(Optional.of(source(true))), ignored -> fetchedContent(), extractor, configuration(5, 500));

        SourceTestResult result = service.test(SOURCE_ID);

        assertEquals(SourceTestStatus.EXTRACTION_FAILED, result.status());
        assertEquals(200, result.httpStatus().orElseThrow());
        assertEquals("application/json", result.responseContentType().orElseThrow());
        assertEquals("invalid selector", result.failureMessage().orElseThrow());
    }

    /** Fail with a stable application exception when the persisted source does not exist. */
    @Test
    void shouldRejectMissingSource() {
        SourceTestService service = new SourceTestService(
                provider(Optional.empty()), ignored -> fetchedContent(), (source, content) -> List.of(), configuration(5, 500));

        assertThrows(SourceTestSourceNotFoundException.class, () -> service.test(SOURCE_ID));
    }

    private static SourceTestConfiguration configuration(int maxPreviewItems, int maxPreviewChars) {
        return new SourceTestConfiguration() {
            @Override
            public int getMaxPreviewItems() {
                return maxPreviewItems;
            }

            @Override
            public int getMaxPreviewContentChars() {
                return maxPreviewChars;
            }
        };
    }

    private static SourceConfigurationProvider provider(Optional<ConfiguredSource> source) {
        return new SourceConfigurationProvider() {
            @Override
            public Optional<ConfiguredSource> findSource(SourceId sourceId) {
                return SOURCE_ID.equals(sourceId) ? source : Optional.empty();
            }

            @Override
            public List<ConfiguredSource> findEnabledSources() {
                return source.filter(ConfiguredSource::enabled).stream().toList();
            }
        };
    }

    private static ConfiguredSource source(boolean enabled) {
        return new ConfiguredSource(SOURCE_ID, "Source", SourceType.REST, SOURCE_URI, enabled, Map.of());
    }

    private static FetchedSourceContent fetchedContent() {
        return new FetchedSourceContent(
                SOURCE_ID,
                SOURCE_URI,
                200,
                Optional.of("application/json"),
                "{}".getBytes(StandardCharsets.UTF_8),
                FETCHED_AT);
    }

    private static ExtractedSourceItem item(String externalId, String content) {
        return new ExtractedSourceItem(
                SOURCE_ID,
                SOURCE_URI.resolve("/" + externalId),
                Optional.of(externalId),
                Optional.of(externalId),
                content,
                "text/plain; charset=UTF-8",
                Optional.empty(),
                FETCHED_AT,
                content.getBytes(StandardCharsets.UTF_8));
    }
}
