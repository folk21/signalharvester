package io.signalharvester.collection.run;

import io.signalharvester.collection.configuration.CollectionClockFactory;
import io.signalharvester.collection.event.RawItemEventPublisher;
import io.signalharvester.collection.event.RawItemPublicationContext;
import io.signalharvester.collection.event.RawItemPublicationException;
import io.signalharvester.collection.event.RawItemPublicationResult;
import io.signalharvester.collection.observability.CollectionObservability;
import io.signalharvester.collection.source.ExtractedSourceItem;
import io.signalharvester.collection.source.extract.SourceItemExtractionException;
import io.signalharvester.collection.source.extract.SourceItemExtractor;
import io.signalharvester.configuration.api.ConfiguredMonitoringProfile;
import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.MonitoringProfileConfigurationProvider;
import io.signalharvester.configuration.api.SourceConfigurationProvider;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes best-effort collection runs for one persisted monitoring profile.
 *
 * <p>One fetched source response may produce multiple semantic items. RSS/Atom feeds therefore emit
 * one raw event per extracted entry, while REST/HTML retain their one-response-per-item behavior.
 * Fetch, extraction, and publication failures remain isolated from unrelated sources/items.</p>
 */
@Singleton
public final class CollectionRunService implements CollectionRunner {

    private static final Logger LOG = LoggerFactory.getLogger(CollectionRunService.class);

    private final MonitoringProfileConfigurationProvider monitoringProfileConfigurationProvider;
    private final SourceConfigurationProvider sourceConfigurationProvider;
    private final SourceFetchCoordinator fetchCoordinator;
    private final SourceItemExtractor itemExtractor;
    private final RawItemEventPublisher eventPublisher;
    private final RawItemIdentityFactory rawItemIdentityFactory;
    private final CollectionRunIdFactory runIdFactory;
    private final CollectionRunHistoryRecorder historyRecorder;
    private final CollectionObservability observability;
    private final Clock clock;

    public CollectionRunService(
            MonitoringProfileConfigurationProvider monitoringProfileConfigurationProvider,
            SourceConfigurationProvider sourceConfigurationProvider,
            SourceFetchCoordinator fetchCoordinator,
            SourceItemExtractor itemExtractor,
            RawItemEventPublisher eventPublisher,
            RawItemIdentityFactory rawItemIdentityFactory,
            CollectionRunIdFactory runIdFactory,
            CollectionRunHistoryRecorder historyRecorder,
            CollectionObservability observability,
            @Named(CollectionClockFactory.COLLECTION_CLOCK) Clock clock) {
        this.monitoringProfileConfigurationProvider = Objects.requireNonNull(
                monitoringProfileConfigurationProvider, "monitoringProfileConfigurationProvider");
        this.sourceConfigurationProvider = Objects.requireNonNull(
                sourceConfigurationProvider, "sourceConfigurationProvider");
        this.fetchCoordinator = Objects.requireNonNull(fetchCoordinator, "fetchCoordinator");
        this.itemExtractor = Objects.requireNonNull(itemExtractor, "itemExtractor");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.rawItemIdentityFactory = Objects.requireNonNull(rawItemIdentityFactory, "rawItemIdentityFactory");
        this.runIdFactory = Objects.requireNonNull(runIdFactory, "runIdFactory");
        this.historyRecorder = Objects.requireNonNull(historyRecorder, "historyRecorder");
        this.observability = Objects.requireNonNull(observability, "observability");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Loads the persisted profile and its enabled member sources, then pipelines bounded fetch
     * completion into extraction and publication. The call is synchronous and completes only after
     * all terminal source/item outcomes are known.
     */
    @Override
    public CollectionRunResult run(CollectionRunRequest request) {
        Objects.requireNonNull(request, "request");
        String profileId = request.monitoringProfileId().value().toString();
        long observedStartNanos = System.nanoTime();
        try (CollectionObservability.RunSpan runSpan = observability.startRun(profileId)) {
            CollectionRunRequest tracedRequest = request.traceparent().isPresent()
                    ? request
                    : new CollectionRunRequest(request.monitoringProfileId(), observability.currentTraceparent());
            try {
                CollectionRunResult result = executeRun(tracedRequest);
                observability.recordRun(
                        result.status().name(), Duration.ofNanos(System.nanoTime() - observedStartNanos));
                return result;
            } catch (RuntimeException | Error failure) {
                runSpan.recordFailure(failure);
                observability.recordRun("FAILED_EXCEPTION", Duration.ofNanos(System.nanoTime() - observedStartNanos));
                throw failure;
            }
        }
    }

    private CollectionRunResult executeRun(CollectionRunRequest request) {
        ConfiguredMonitoringProfile profile = monitoringProfileConfigurationProvider
                .findProfile(request.monitoringProfileId())
                .orElseThrow(() -> new CollectionProfileNotFoundException(request.monitoringProfileId()));
        String runId = runIdFactory.nextId();
        Instant startedAt = clock.instant();
        List<ConfiguredSource> sources = profile.sourceIds().stream()
                .map(sourceId -> sourceConfigurationProvider.findSource(sourceId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Monitoring profile references missing source: " + sourceId.value())))
                .filter(ConfiguredSource::enabled)
                .toList();
        LOG.info("Starting collection run {} profile={} category={} sources={}",
                runId, profile.id().value(), profile.informationCategory(), sources.size());

        @SuppressWarnings("unchecked")
        List<CollectionSourceResult>[] terminalResults = new List[sources.size()];
        fetchCoordinator.fetchEach(
                sources,
                (sourceIndex, outcome) ->
                        terminalResults[sourceIndex] = toSourceResults(runId, request, profile, outcome));
        List<CollectionSourceResult> sourceResults = orderedResults(terminalResults);

        Instant finishedAt = clock.instant();
        CollectionRunStatus status = aggregateStatus(sourceResults);
        long published = sourceResults.stream()
                .filter(result -> result.status() == CollectionSourceStatus.PUBLISHED)
                .count();
        long failed = sourceResults.stream().filter(CollectionSourceResult::failed).count();
        LOG.info("Completed collection run {} status={} publishedItems={} failures={}",
                runId, status, published, failed);
        CollectionRunResult result = new CollectionRunResult(
                runId,
                profile.id().value().toString(),
                profile.informationCategory(),
                startedAt,
                finishedAt,
                status,
                sourceResults);
        historyRecorder.record(result);
        return result;
    }

    private List<CollectionSourceResult> toSourceResults(
            String runId,
            CollectionRunRequest request,
            ConfiguredMonitoringProfile profile,
            SourceFetchOutcome outcome) {
        if (outcome instanceof SourceFetchOutcome.Failure failure) {
            LOG.warn("Collection run {} source {} fetch failed: {}",
                    runId, failure.source().id().value(), failureMessage(failure.cause()));
            return List.of(new CollectionSourceResult(
                    failure.source().id(),
                    CollectionSourceStatus.FETCH_FAILED,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(failureMessage(failure.cause()))));
        }

        SourceFetchOutcome.Success success = (SourceFetchOutcome.Success) outcome;
        List<ExtractedSourceItem> items;
        try {
            items = itemExtractor.extract(success.source(), success.content());
        } catch (SourceItemExtractionException failure) {
            LOG.warn("Collection run {} source {} extraction failed: {}",
                    runId, success.source().id().value(), failureMessage(failure));
            return List.of(new CollectionSourceResult(
                    success.source().id(),
                    CollectionSourceStatus.EXTRACTION_FAILED,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(failureMessage(failure))));
        }

        if (items.isEmpty()) {
            LOG.info("Collection run {} source {} extracted no items", runId, success.source().id().value());
            return List.of(new CollectionSourceResult(
                    success.source().id(),
                    CollectionSourceStatus.NO_ITEMS,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()));
        }

        List<CollectionSourceResult> results = new ArrayList<>(items.size());
        for (ExtractedSourceItem item : items) {
            results.add(publishItem(runId, request, profile, item));
        }
        return List.copyOf(results);
    }

    private CollectionSourceResult publishItem(
            String runId,
            CollectionRunRequest request,
            ConfiguredMonitoringProfile profile,
            ExtractedSourceItem item) {
        String rawItemId = rawItemIdentityFactory.identityFor(item);
        RawItemPublicationContext publicationContext = new RawItemPublicationContext(
                rawItemId,
                runId,
                profile.id().value().toString(),
                profile.informationCategory(),
                profile.analysisSettings(),
                request.traceparent());
        try {
            RawItemPublicationResult publication = eventPublisher.publish(item, publicationContext);
            return new CollectionSourceResult(
                    item.sourceId(),
                    CollectionSourceStatus.PUBLISHED,
                    Optional.of(publication.rawItemId()),
                    Optional.of(publication.eventId()),
                    Optional.empty());
        } catch (RawItemPublicationException failure) {
            LOG.warn("Collection run {} source {} publication failed rawItemId={}: {}",
                    runId, item.sourceId().value(), rawItemId, failureMessage(failure));
            return new CollectionSourceResult(
                    item.sourceId(),
                    CollectionSourceStatus.PUBLICATION_FAILED,
                    Optional.of(rawItemId),
                    Optional.empty(),
                    Optional.of(failureMessage(failure)));
        }
    }

    private static List<CollectionSourceResult> orderedResults(List<CollectionSourceResult>[] resultsBySource) {
        List<CollectionSourceResult> ordered = new ArrayList<>();
        for (int index = 0; index < resultsBySource.length; index++) {
            List<CollectionSourceResult> sourceResults = Objects.requireNonNull(
                    resultsBySource[index], "Missing terminal source result at index " + index);
            ordered.addAll(sourceResults);
        }
        return List.copyOf(ordered);
    }

    private static CollectionRunStatus aggregateStatus(List<CollectionSourceResult> sourceResults) {
        long failures = sourceResults.stream().filter(CollectionSourceResult::failed).count();
        if (failures == 0) {
            return CollectionRunStatus.SUCCEEDED;
        }
        long nonFailures = sourceResults.size() - failures;
        return nonFailures == 0 ? CollectionRunStatus.FAILED : CollectionRunStatus.PARTIALLY_SUCCEEDED;
    }

    private static String failureMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
