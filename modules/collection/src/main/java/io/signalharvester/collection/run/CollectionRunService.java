package io.signalharvester.collection.run;

import io.signalharvester.collection.api.CollectionRunRequest;
import io.signalharvester.collection.api.CollectionRunResult;
import io.signalharvester.collection.api.CollectionRunStatus;
import io.signalharvester.collection.api.CollectionRunner;
import io.signalharvester.collection.api.CollectionSourceResult;
import io.signalharvester.collection.api.CollectionSourceStatus;
import io.signalharvester.collection.configuration.CollectionClockFactory;
import io.signalharvester.collection.event.RawItemEventPublisher;
import io.signalharvester.collection.event.RawItemPublicationContext;
import io.signalharvester.collection.event.RawItemPublicationException;
import io.signalharvester.collection.event.RawItemPublicationResult;
import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceConfigurationProvider;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes the first explicit best-effort collection run across all currently enabled sources.
 *
 * <p>The run id is also used as the Kafka correlation id. Source-level fetch or publication failures
 * are retained as terminal outcomes while unrelated sources continue. Monitoring-profile source
 * membership remains deferred; completed operational run history is persisted by the collection module. Because Kafka
 * publication is acknowledged/blocking, callers must invoke this use case from a blocking/Virtual-Thread
 * workflow rather than a Netty event-loop thread.</p>
 */
@Singleton
public final class CollectionRunService implements CollectionRunner {

    private static final Logger LOG = LoggerFactory.getLogger(CollectionRunService.class);

    private final SourceConfigurationProvider sourceConfigurationProvider;
    private final SourceFetchCoordinator fetchCoordinator;
    private final RawItemEventPublisher eventPublisher;
    private final RawItemIdentityFactory rawItemIdentityFactory;
    private final CollectionRunIdFactory runIdFactory;
    private final CollectionRunHistoryRecorder historyRecorder;
    private final Clock clock;

    public CollectionRunService(
            SourceConfigurationProvider sourceConfigurationProvider,
            SourceFetchCoordinator fetchCoordinator,
            RawItemEventPublisher eventPublisher,
            RawItemIdentityFactory rawItemIdentityFactory,
            CollectionRunIdFactory runIdFactory,
            CollectionRunHistoryRecorder historyRecorder,
            @Named(CollectionClockFactory.COLLECTION_CLOCK) Clock clock) {
        this.sourceConfigurationProvider = Objects.requireNonNull(
                sourceConfigurationProvider, "sourceConfigurationProvider");
        this.fetchCoordinator = Objects.requireNonNull(fetchCoordinator, "fetchCoordinator");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.rawItemIdentityFactory = Objects.requireNonNull(rawItemIdentityFactory, "rawItemIdentityFactory");
        this.runIdFactory = Objects.requireNonNull(runIdFactory, "runIdFactory");
        this.historyRecorder = Objects.requireNonNull(historyRecorder, "historyRecorder");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Loads enabled sources and pipelines bounded fetch completion into acknowledged publication.
     * The call is synchronous and completes only after all terminal source outcomes are known. Large
     * fetched payloads are released after terminal handling instead of being retained for the whole run.
     *
     * @param request caller-owned run context until monitoring profiles become persistent
     * @return explicit run identity, timing, aggregate status, and per-source outcomes
     */
    @Override
    public CollectionRunResult run(CollectionRunRequest request) {
        Objects.requireNonNull(request, "request");
        String runId = runIdFactory.nextId();
        Instant startedAt = clock.instant();
        List<ConfiguredSource> sources = List.copyOf(sourceConfigurationProvider.findEnabledSources());
        LOG.info("Starting collection run {} profile={} category={} sources={}",
                runId, request.monitoringProfileId(), request.informationCategory(), sources.size());
        CollectionSourceResult[] terminalResults = new CollectionSourceResult[sources.size()];
        fetchCoordinator.fetchEach(
                sources,
                (sourceIndex, outcome) -> terminalResults[sourceIndex] = toSourceResult(runId, request, outcome));
        List<CollectionSourceResult> sourceResults = orderedSourceResults(terminalResults);

        Instant finishedAt = clock.instant();
        CollectionRunStatus status = aggregateStatus(sourceResults);
        long published = sourceResults.stream()
                .filter(result -> result.status() == CollectionSourceStatus.PUBLISHED)
                .count();
        LOG.info("Completed collection run {} status={} published={} failed={}",
                runId, status, published, sourceResults.size() - published);
        CollectionRunResult result = new CollectionRunResult(
                runId,
                request.monitoringProfileId(),
                request.informationCategory(),
                startedAt,
                finishedAt,
                status,
                sourceResults);
        historyRecorder.record(result);
        return result;
    }

    private CollectionSourceResult toSourceResult(
            String runId,
            CollectionRunRequest request,
            SourceFetchOutcome outcome) {
        if (outcome instanceof SourceFetchOutcome.Failure failure) {
            LOG.warn("Collection run {} source {} fetch failed: {}",
                    runId, failure.source().id().value(), failureMessage(failure.cause()));
            return new CollectionSourceResult(
                    failure.source().id(),
                    CollectionSourceStatus.FETCH_FAILED,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(failureMessage(failure.cause())));
        }

        SourceFetchOutcome.Success success = (SourceFetchOutcome.Success) outcome;
        String rawItemId = rawItemIdentityFactory.identityFor(success.content());
        RawItemPublicationContext publicationContext = new RawItemPublicationContext(
                rawItemId,
                runId,
                request.monitoringProfileId(),
                request.informationCategory(),
                request.traceparent());
        try {
            RawItemPublicationResult publication = eventPublisher.publish(success.content(), publicationContext);
            return new CollectionSourceResult(
                    success.source().id(),
                    CollectionSourceStatus.PUBLISHED,
                    Optional.of(publication.rawItemId()),
                    Optional.of(publication.eventId()),
                    Optional.empty());
        } catch (RawItemPublicationException failure) {
            LOG.warn("Collection run {} source {} publication failed rawItemId={}: {}",
                    runId, success.source().id().value(), rawItemId, failureMessage(failure));
            return new CollectionSourceResult(
                    success.source().id(),
                    CollectionSourceStatus.PUBLICATION_FAILED,
                    Optional.of(rawItemId),
                    Optional.empty(),
                    Optional.of(failureMessage(failure)));
        }
    }

    private static List<CollectionSourceResult> orderedSourceResults(CollectionSourceResult[] results) {
        List<CollectionSourceResult> ordered = new ArrayList<>(results.length);
        for (int index = 0; index < results.length; index++) {
            ordered.add(Objects.requireNonNull(
                    results[index],
                    "Missing terminal source result at index " + index));
        }
        return List.copyOf(ordered);
    }

    private static CollectionRunStatus aggregateStatus(List<CollectionSourceResult> sourceResults) {
        long successful = sourceResults.stream()
                .filter(result -> result.status() == CollectionSourceStatus.PUBLISHED)
                .count();
        long failed = sourceResults.size() - successful;
        if (failed == 0) {
            return CollectionRunStatus.SUCCEEDED;
        }
        if (successful == 0) {
            return CollectionRunStatus.FAILED;
        }
        return CollectionRunStatus.PARTIALLY_SUCCEEDED;
    }

    private static String failureMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
