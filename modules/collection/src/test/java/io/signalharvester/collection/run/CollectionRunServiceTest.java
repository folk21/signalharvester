package io.signalharvester.collection.run;

import io.signalharvester.collection.api.CollectionRunRequest;
import io.signalharvester.collection.api.CollectionRunResult;
import io.signalharvester.collection.api.CollectionRunStatus;
import io.signalharvester.collection.api.CollectionSourceResult;
import io.signalharvester.collection.api.CollectionSourceStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.signalharvester.collection.event.RawItemEventPublisher;
import io.signalharvester.collection.event.RawItemPublicationContext;
import io.signalharvester.collection.event.RawItemPublicationException;
import io.signalharvester.collection.event.RawItemPublicationResult;
import io.signalharvester.collection.source.ExternalSourceClient;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.collection.source.SourceFetchException;
import io.signalharvester.configuration.api.ConfiguredSource;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CollectionRunServiceTest {

    private static final String RUN_ID = "00000000-0000-0000-0000-000000000601";
    private static final Instant RUN_TIME = Instant.parse("2026-09-10T18:00:00Z");

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
            assertTrue(publisher.contexts.stream().allMatch(context -> "profile-1".equals(context.monitoringProfileId())));
            assertTrue(publisher.contexts.stream().allMatch(context -> "JOB".equals(context.informationCategory())));
        }
    }

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

    @Test
    void shouldContinuePublicationAfterOneKafkaFailure() {
        List<ConfiguredSource> sources = List.of(source("one"), source("two"), source("three"));
        AtomicInteger publication = new AtomicInteger();
        RawItemEventPublisher publisher = (content, context) -> {
            int current = publication.incrementAndGet();
            if (current == 2) {
                throw new RawItemPublicationException(
                        context.rawItemId(), context.correlationId(), "raw-items", "synthetic Kafka failure",
                        new IllegalStateException("broker failure"));
            }
            return new RawItemPublicationResult("event-" + current, context.rawItemId(), "raw-items");
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

    @Test
    void shouldSucceedWithoutWorkWhenNoSourcesAreEnabled() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CollectionRunService service = service(
                    List.of(),
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
        SourceFetchCoordinator coordinator = new SourceFetchCoordinator(client, () -> 2, executor);
        return new CollectionRunService(
                provider,
                coordinator,
                publisher,
                new RawItemIdentityFactory(),
                new CollectionRunIdFactory(() -> UUID.fromString(RUN_ID)),
                new InMemoryHistoryStore(),
                Clock.fixed(RUN_TIME, ZoneOffset.UTC));
    }

    private static CollectionRunRequest request() {
        return new CollectionRunRequest("profile-1", "JOB", Optional.empty());
    }

    private static ConfiguredSource source(String name) {
        return new ConfiguredSource(
                SourceId.of(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8))),
                name,
                SourceType.REST,
                URI.create("https://example.test/" + name),
                true,
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

    private static final class InMemoryHistoryStore implements CollectionRunHistoryStore {
        private CollectionRunResult result;

        @Override
        public void save(CollectionRunResult result) {
            this.result = result;
        }

        @Override
        public List<CollectionRunResult> findRecent(int limit) {
            return result == null ? List.of() : List.of(result);
        }

        @Override
        public Optional<CollectionRunResult> findById(String collectionRunId) {
            return Optional.ofNullable(result).filter(value -> value.collectionRunId().equals(collectionRunId));
        }
    }

    private static final class RecordingPublisher implements RawItemEventPublisher {
        private final List<RawItemPublicationContext> contexts = new ArrayList<>();

        @Override
        public RawItemPublicationResult publish(FetchedSourceContent content, RawItemPublicationContext context) {
            contexts.add(context);
            return new RawItemPublicationResult("event-" + contexts.size(), context.rawItemId(), "raw-items");
        }
    }
}
