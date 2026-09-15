package io.signalharvester.collection.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.signalharvester.collection.event.RawItemEventPublisher;
import io.signalharvester.collection.event.RawItemPublicationContext;
import io.signalharvester.collection.event.RawItemPublicationException;
import io.signalharvester.collection.event.RawItemPublicationResult;
import io.signalharvester.collection.observability.CollectionObservability;
import io.signalharvester.collection.source.ExternalSourceClient;
import io.signalharvester.collection.source.ExtractedSourceItem;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.collection.source.extract.DefaultSourceItemExtractor;
import io.signalharvester.collection.source.extract.HtmlSourceItemExtractor;
import io.signalharvester.collection.source.extract.JsonSourceItemExtractor;
import io.signalharvester.collection.source.extract.RssAtomItemExtractor;
import io.signalharvester.collection.source.extract.SourceItemExtractor;
import io.signalharvester.collection.source.SourceFetchException;
import io.signalharvester.configuration.api.ConfiguredMonitoringProfile;
import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.MonitoringProfileConfigurationProvider;
import io.signalharvester.configuration.api.MonitoringProfileId;
import io.signalharvester.configuration.api.SourceConfigurationProvider;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.api.SourceType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Verifies orchestration semantics of {@link CollectionRunService}, including bounded fetch-to-publication
 * behavior, partial failures, run correlation, and deterministic terminal-result ordering.
 *
 * <p>Related specifications: {@code backend-collection-run-orchestration}, {@code backend-rss-atom-extraction}.</p>
 */
class CollectionRunServiceTest {

    private static final String RUN_ID = "00000000-0000-0000-0000-000000000601";
    private static final Instant RUN_TIME = Instant.parse("2026-09-10T18:00:00Z");
    private static final MonitoringProfileId PROFILE_ID = MonitoringProfileId.of(
            UUID.fromString("00000000-0000-0000-0000-000000000501"));
    private static final String RAW_ITEM_TOPIC = "raw-items";
    private static final String EVENT_ID_PREFIX = "event-";

    /**
     * Publish all enabled sources with run correlation.
     */
    @Test
    void shouldPublishAllEnabledSourcesWithRunCorrelation() {
        List<ConfiguredSource> sources = List.of(source("one"), source("two"));
        RecordingPublisher publisher = new RecordingPublisher();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CollectionRunService service = service(sources, CollectionRunServiceTest::content, publisher, executor);

            CollectionRunResult result = service.run(request());

            assertEquals(RUN_ID, result.collectionRunId());
            assertEquals(CollectionRunStatus.SUCCEEDED, result.status());
            assertEquals(2, result.publishedCount());
            assertEquals(0, result.failedCount());
            assertEquals(List.of(sources.get(0).id(), sources.get(1).id()), result.sources().stream()
                    .map(CollectionSourceResult::sourceId)
                    .toList());
            assertEquals(2, publisher.contexts.size());
            assertTrue(publisher.contexts.stream().allMatch(context -> RUN_ID.equals(context.correlationId())));
            assertTrue(publisher.contexts.stream().allMatch(context -> PROFILE_ID.value().toString().equals(context.monitoringProfileId())));
            assertTrue(publisher.contexts.stream().allMatch(context -> "JOB".equals(context.informationCategory())));
        }
    }

    /**
     * Publish completed payload before fetching beyond concurrency window.
     */
    @Test
    void shouldPublishCompletedPayloadBeforeFetchingBeyondConcurrencyWindow() throws Exception {
        List<ConfiguredSource> sources = List.of(source("one"), source("two"), source("three"));
        CountDownLatch releaseFirstFetch = new CountDownLatch(1);
        CountDownLatch thirdFetchStarted = new CountDownLatch(1);
        CountDownLatch secondPublicationStarted = new CountDownLatch(1);
        CountDownLatch releaseSecondPublication = new CountDownLatch(1);

        ExternalSourceClient client = source -> {
            if ("one".equals(source.name())) {
                await(releaseFirstFetch);
            } else if ("three".equals(source.name())) {
                thirdFetchStarted.countDown();
            }
            return content(source);
        };
        RawItemEventPublisher publisher = (content, context) -> {
            if (content.url().getPath().endsWith("/two")) {
                secondPublicationStarted.countDown();
                await(releaseSecondPublication);
            }
            return new RawItemPublicationResult(EVENT_ID_PREFIX + context.rawItemId(), context.rawItemId(), RAW_ITEM_TOPIC);
        };

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CollectionRunService service = service(sources, client, publisher, executor);
            CompletableFuture<CollectionRunResult> result = CompletableFuture.supplyAsync(() -> service.run(request()));

            assertTrue(secondPublicationStarted.await(2, TimeUnit.SECONDS));
            assertTrue(!thirdFetchStarted.await(100, TimeUnit.MILLISECONDS),
                    "third fetch must wait while the completed payload is being published");

            releaseSecondPublication.countDown();
            assertTrue(thirdFetchStarted.await(2, TimeUnit.SECONDS));
            releaseFirstFetch.countDown();

            assertEquals(List.of(sources.get(0).id(), sources.get(1).id(), sources.get(2).id()),
                    result.get(2, TimeUnit.SECONDS).sources().stream()
                            .map(CollectionSourceResult::sourceId)
                            .toList());
        }
    }

    /**
     * Report partial success and continue after fetch failure.
     */
    @Test
    void shouldReportPartialSuccessAndContinueAfterFetchFailure() {
        List<ConfiguredSource> sources = List.of(source("one"), source("two"), source("three"));
        ExternalSourceClient client = source -> {
            if ("two".equals(source.name())) {
                throw new SourceFetchException(source.id(), source.location(), "synthetic fetch failure");
            }
            return content(source);
        };
        RecordingPublisher publisher = new RecordingPublisher();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CollectionRunService service = service(sources, client, publisher, executor);

            CollectionRunResult result = service.run(request());

            assertEquals(CollectionRunStatus.PARTIALLY_SUCCEEDED, result.status());
            assertEquals(2, result.publishedCount());
            assertEquals(1, result.failedCount());
            assertEquals(CollectionSourceStatus.FETCH_FAILED, result.sources().get(1).status());
            assertEquals("synthetic fetch failure", result.sources().get(1).failureMessage().orElseThrow());
            assertEquals(2, publisher.contexts.size());
        }
    }

    /**
     * Continue publication after one Kafka failure.
     */
    @Test
    void shouldContinuePublicationAfterOneKafkaFailure() {
        List<ConfiguredSource> sources = List.of(source("one"), source("two"), source("three"));
        SourceId failingSourceId = sources.get(1).id();
        AtomicInteger publication = new AtomicInteger();
        RawItemEventPublisher publisher = (content, context) -> {
            int current = publication.incrementAndGet();
            if (content.sourceId().equals(failingSourceId)) {
                throw new RawItemPublicationException(
                        context.rawItemId(), context.correlationId(), RAW_ITEM_TOPIC, "synthetic Kafka failure",
                        new IllegalStateException("broker failure"));
            }
            return new RawItemPublicationResult(EVENT_ID_PREFIX + current, context.rawItemId(), RAW_ITEM_TOPIC);
        };

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CollectionRunService service = service(sources, CollectionRunServiceTest::content, publisher, executor);

            CollectionRunResult result = service.run(request());

            assertEquals(3, publication.get());
            assertEquals(CollectionRunStatus.PARTIALLY_SUCCEEDED, result.status());
            assertEquals(CollectionSourceStatus.PUBLISHED, result.sources().get(0).status());
            assertEquals(CollectionSourceStatus.PUBLICATION_FAILED, result.sources().get(1).status());
            assertTrue(result.sources().get(1).rawItemId().isPresent());
            assertTrue(result.sources().get(1).eventId().isEmpty());
            assertEquals(CollectionSourceStatus.PUBLISHED, result.sources().get(2).status());
        }
    }


    /**
     * Publish one terminal outcome per extracted RSS entry while preserving source ordering.
     */
    @Test
    void shouldPublishEachExtractedRssEntry() {
        ConfiguredSource rss = new ConfiguredSource(
                SourceId.of(UUID.fromString("00000000-0000-0000-0000-000000000777")),
                "rss",
                SourceType.RSS,
                URI.create("https://example.test/feed.xml"),
                true,
                Map.of());
        String feed = """
                <rss version="2.0"><channel>
                  <item><guid>one</guid><title>One</title><link>https://example.test/one</link><description>Java one</description></item>
                  <item><guid>two</guid><title>Two</title><link>https://example.test/two</link><description>Java two</description></item>
                </channel></rss>
                """;
        ExternalSourceClient client = ignored -> new FetchedSourceContent(
                rss.id(), rss.location(), 200, Optional.of("application/rss+xml"),
                feed.getBytes(StandardCharsets.UTF_8), RUN_TIME);
        RecordingPublisher publisher = new RecordingPublisher();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CollectionRunService service = service(List.of(rss), client, publisher, executor);

            CollectionRunResult result = service.run(request());

            assertEquals(CollectionRunStatus.SUCCEEDED, result.status());
            assertEquals(2, result.publishedCount());
            assertEquals(0, result.failedCount());
            assertEquals(List.of(rss.id(), rss.id()), result.sources().stream()
                    .map(CollectionSourceResult::sourceId)
                    .toList());
            assertEquals(2, publisher.contexts.size());
        }
    }

    /**
     * Record a valid empty feed without treating it as a failure.
     */
    @Test
    void shouldRecordNoItemsForEmptyRssFeed() {
        ConfiguredSource rss = new ConfiguredSource(
                SourceId.of(UUID.fromString("00000000-0000-0000-0000-000000000778")),
                "rss-empty",
                SourceType.RSS,
                URI.create("https://example.test/empty.xml"),
                true,
                Map.of());
        ExternalSourceClient client = ignored -> new FetchedSourceContent(
                rss.id(), rss.location(), 200, Optional.of("application/rss+xml"),
                "<rss version=\"2.0\"><channel/></rss>".getBytes(StandardCharsets.UTF_8), RUN_TIME);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CollectionRunResult result = service(List.of(rss), client, new RecordingPublisher(), executor).run(request());

            assertEquals(CollectionRunStatus.SUCCEEDED, result.status());
            assertEquals(0, result.publishedCount());
            assertEquals(0, result.failedCount());
            assertEquals(CollectionSourceStatus.NO_ITEMS, result.sources().getFirst().status());
        }
    }

    /**
     * Isolate malformed feed extraction failure from otherwise successful sources.
     */
    @Test
    void shouldContinueAfterRssExtractionFailure() {
        ConfiguredSource rss = new ConfiguredSource(
                SourceId.of(UUID.fromString("00000000-0000-0000-0000-000000000779")),
                "rss-broken",
                SourceType.RSS,
                URI.create("https://example.test/broken.xml"),
                true,
                Map.of());
        ConfiguredSource rest = source("rest-ok");
        ExternalSourceClient client = configured -> configured.type() == SourceType.RSS
                ? new FetchedSourceContent(configured.id(), configured.location(), 200,
                        Optional.of("application/rss+xml"), "<rss><channel><item>".getBytes(StandardCharsets.UTF_8), RUN_TIME)
                : content(configured);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CollectionRunResult result = service(
                    List.of(rss, rest), client, new RecordingPublisher(), executor).run(request());

            assertEquals(CollectionRunStatus.PARTIALLY_SUCCEEDED, result.status());
            assertEquals(1, result.publishedCount());
            assertEquals(1, result.failedCount());
            assertEquals(CollectionSourceStatus.EXTRACTION_FAILED, result.sources().getFirst().status());
            assertEquals(CollectionSourceStatus.PUBLISHED, result.sources().get(1).status());
        }
    }

    /**
     * Report failed when every source fails.
     */
    @Test
    void shouldReportFailedWhenEverySourceFails() {
        List<ConfiguredSource> sources = List.of(source("one"), source("two"));
        ExternalSourceClient client = source -> {
            throw new SourceFetchException(source.id(), source.location(), "unavailable");
        };

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CollectionRunService service = service(sources, client, new RecordingPublisher(), executor);

            CollectionRunResult result = service.run(request());

            assertEquals(CollectionRunStatus.FAILED, result.status());
            assertEquals(0, result.publishedCount());
            assertEquals(2, result.failedCount());
        }
    }

    /**
     * Succeed without work when no sources are enabled.
     */
    @Test
    void shouldSucceedWithoutWorkWhenNoSourcesAreEnabled() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CollectionRunService service = service(
                    List.of(source("disabled", false)),
                    source -> { throw new AssertionError("fetch must not run"); },
                    (content, context) -> { throw new AssertionError("publish must not run"); },
                    executor);

            CollectionRunResult result = service.run(request());

            assertEquals(CollectionRunStatus.SUCCEEDED, result.status());
            assertTrue(result.sources().isEmpty());
        }
    }

    private static CollectionRunService service(
            List<ConfiguredSource> sources,
            ExternalSourceClient client,
            RawItemEventPublisher publisher,
            ExecutorService executor) {
        SourceConfigurationProvider provider = new SourceConfigurationProvider() {
            @Override
            public Optional<ConfiguredSource> findSource(SourceId sourceId) {
                return sources.stream().filter(source -> source.id().equals(sourceId)).findFirst();
            }

            @Override
            public List<ConfiguredSource> findEnabledSources() {
                return sources;
            }
        };
        ConfiguredMonitoringProfile profile = new ConfiguredMonitoringProfile(
                PROFILE_ID,
                "Test profile",
                "JOB",
                true,
                5,
                sources.stream().map(ConfiguredSource::id).toList(),
                Map.of());
        MonitoringProfileConfigurationProvider profileProvider = new MonitoringProfileConfigurationProvider() {
            @Override
            public Optional<ConfiguredMonitoringProfile> findProfile(MonitoringProfileId profileId) {
                return PROFILE_ID.equals(profileId) ? Optional.of(profile) : Optional.empty();
            }

            @Override
            public List<ConfiguredMonitoringProfile> findEnabledProfiles() {
                return List.of(profile);
            }
        };
        CollectionObservability observability = new CollectionObservability(Optional.empty(), Optional.empty());
        SourceFetchCoordinator coordinator = new SourceFetchCoordinator(client, () -> 2, observability, executor);
        return new CollectionRunService(
                profileProvider,
                provider,
                coordinator,
                defaultExtractor(),
                publisher,
                new RawItemIdentityFactory(),
                new CollectionRunIdFactory(() -> UUID.fromString(RUN_ID)),
                new InMemoryHistoryRecorder(),
                observability,
                Clock.fixed(RUN_TIME, ZoneOffset.UTC));
    }

    private static SourceItemExtractor defaultExtractor() {
        return new DefaultSourceItemExtractor(
                new RssAtomItemExtractor(() -> 500),
                new JsonSourceItemExtractor(() -> 500),
                new HtmlSourceItemExtractor(() -> 500));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("synthetic test wait interrupted", interrupted);
        }
    }

    private static CollectionRunRequest request() {
        return new CollectionRunRequest(PROFILE_ID, Optional.empty());
    }

    private static ConfiguredSource source(String name) {
        return source(name, true);
    }

    private static ConfiguredSource source(String name, boolean enabled) {
        return new ConfiguredSource(
                SourceId.of(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8))),
                name,
                SourceType.REST,
                URI.create("https://example.test/" + name),
                enabled,
                Map.of());
    }

    private static FetchedSourceContent content(ConfiguredSource source) {
        return new FetchedSourceContent(
                source.id(),
                source.location(),
                200,
                Optional.of("text/plain; charset=UTF-8"),
                ("payload-" + source.name()).getBytes(StandardCharsets.UTF_8),
                RUN_TIME);
    }

    private static final class InMemoryHistoryRecorder implements CollectionRunHistoryRecorder {
        @Override
        public void record(CollectionRunResult result) {
            // Intentionally no-op: unit tests assert run behavior, not persistence.
        }
    }

    private static final class RecordingPublisher implements RawItemEventPublisher {
        private final List<RawItemPublicationContext> contexts = new ArrayList<>();

        @Override
        public RawItemPublicationResult publish(ExtractedSourceItem content, RawItemPublicationContext context) {
            contexts.add(context);
            return new RawItemPublicationResult(EVENT_ID_PREFIX + contexts.size(), context.rawItemId(), RAW_ITEM_TOPIC);
        }
    }
}
