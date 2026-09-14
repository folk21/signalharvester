package io.signalharvester.collection.sourcetest;

import io.signalharvester.collection.source.ExtractedSourceItem;
import io.signalharvester.collection.source.ExternalSourceClient;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.collection.source.SourceFetchException;
import io.signalharvester.collection.source.extract.SourceItemExtractionException;
import io.signalharvester.collection.source.extract.SourceItemExtractor;
import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceConfigurationProvider;
import io.signalharvester.configuration.api.SourceId;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Executes diagnostic fetch and extraction through the same collection ports used by normal runs. */
@Singleton
public final class SourceTestService implements SourceTester {

    private final SourceConfigurationProvider sourceConfigurationProvider;
    private final ExternalSourceClient externalSourceClient;
    private final SourceItemExtractor sourceItemExtractor;
    private final SourceTestConfiguration configuration;

    public SourceTestService(
            SourceConfigurationProvider sourceConfigurationProvider,
            ExternalSourceClient externalSourceClient,
            SourceItemExtractor sourceItemExtractor,
            SourceTestConfiguration configuration) {
        this.sourceConfigurationProvider = Objects.requireNonNull(
                sourceConfigurationProvider, "sourceConfigurationProvider");
        this.externalSourceClient = Objects.requireNonNull(externalSourceClient, "externalSourceClient");
        this.sourceItemExtractor = Objects.requireNonNull(sourceItemExtractor, "sourceItemExtractor");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    @Override
    public SourceTestResult test(SourceId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        ConfiguredSource source = sourceConfigurationProvider.findSource(sourceId)
                .orElseThrow(() -> new SourceTestSourceNotFoundException(sourceId));

        long fetchStarted = System.nanoTime();
        FetchedSourceContent fetched;
        try {
            fetched = externalSourceClient.fetch(source);
        } catch (SourceFetchException failure) {
            return new SourceTestResult(
                    sourceId,
                    SourceTestStatus.FETCH_FAILED,
                    failure.statusCode().isPresent()
                            ? Optional.of(failure.statusCode().getAsInt())
                            : Optional.empty(),
                    Optional.empty(),
                    0,
                    elapsedMillis(fetchStarted),
                    0,
                    0,
                    List.of(),
                    Optional.of(failureMessage(failure)));
        }
        long fetchDurationMs = elapsedMillis(fetchStarted);

        long extractionStarted = System.nanoTime();
        List<ExtractedSourceItem> items;
        try {
            items = sourceItemExtractor.extract(source, fetched);
        } catch (SourceItemExtractionException failure) {
            return new SourceTestResult(
                    sourceId,
                    SourceTestStatus.EXTRACTION_FAILED,
                    Optional.of(fetched.statusCode()),
                    fetched.contentType(),
                    fetched.body().length,
                    fetchDurationMs,
                    elapsedMillis(extractionStarted),
                    0,
                    List.of(),
                    Optional.of(failureMessage(failure)));
        }
        long extractionDurationMs = elapsedMillis(extractionStarted);

        return new SourceTestResult(
                sourceId,
                SourceTestStatus.SUCCEEDED,
                Optional.of(fetched.statusCode()),
                fetched.contentType(),
                fetched.body().length,
                fetchDurationMs,
                extractionDurationMs,
                items.size(),
                preview(items),
                Optional.empty());
    }

    private List<SourceTestPreviewItem> preview(List<ExtractedSourceItem> items) {
        int limit = Math.min(items.size(), configuration.getMaxPreviewItems());
        List<SourceTestPreviewItem> preview = new ArrayList<>(limit);
        for (int index = 0; index < limit; index++) {
            ExtractedSourceItem item = items.get(index);
            String content = item.content();
            int maxChars = configuration.getMaxPreviewContentChars();
            int codePointCount = content.codePointCount(0, content.length());
            boolean truncated = codePointCount > maxChars;
            int endIndex = truncated ? content.offsetByCodePoints(0, maxChars) : content.length();
            String boundedContent = content.substring(0, endIndex);
            preview.add(new SourceTestPreviewItem(
                    item.externalId(),
                    item.title(),
                    item.url(),
                    boundedContent,
                    item.contentType(),
                    item.publishedAt(),
                    truncated));
        }
        return List.copyOf(preview);
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String failureMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
