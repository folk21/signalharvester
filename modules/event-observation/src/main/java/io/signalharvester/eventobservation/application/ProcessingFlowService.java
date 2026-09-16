package io.signalharvester.eventobservation.application;

import io.signalharvester.eventobservation.application.ProcessingFlow.Scope;
import io.signalharvester.eventobservation.model.ObservedEvent;
import jakarta.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Queries retained event evidence and delegates deterministic processing-graph reconstruction. */
@Singleton
public final class ProcessingFlowService implements ProcessingFlowQuery {

    private final EventObservationQuery eventQuery;

    public ProcessingFlowService(EventObservationQuery eventQuery) {
        this.eventQuery = Objects.requireNonNull(eventQuery, "eventQuery");
    }

    @Override
    public ProcessingFlow collectionRun(String collectionRunId) {
        String runId = requireNonBlank(collectionRunId, "collectionRunId");
        List<ObservedEvent> events = eventQuery.recent(
                ProcessingFlowReconstructor.criteria(runId, Optional.empty()),
                EventObservationService.MAX_LIMIT);
        if (events.isEmpty()) {
            throw new ProcessingFlowNotFoundException("No retained events found for collection run " + runId);
        }
        return ProcessingFlowReconstructor.reconstruct(
                Scope.COLLECTION_RUN,
                runId,
                Optional.empty(),
                events,
                events.size() == EventObservationService.MAX_LIMIT);
    }

    @Override
    public ProcessingFlow item(String collectionRunId, String itemId) {
        String runId = requireNonBlank(collectionRunId, "collectionRunId");
        String selectedItemId = requireNonBlank(itemId, "itemId");

        List<ObservedEvent> matches = eventQuery.recent(
                ProcessingFlowReconstructor.criteria(runId, Optional.of(selectedItemId)),
                EventObservationService.MAX_LIMIT);
        if (matches.isEmpty()) {
            throw new ProcessingFlowNotFoundException(
                    "No retained events found for item " + selectedItemId + " in collection run " + runId);
        }

        List<ObservedEvent> runEvents = eventQuery.recent(
                ProcessingFlowReconstructor.criteria(runId, Optional.empty()),
                EventObservationService.MAX_LIMIT);
        Map<Long, ObservedEvent> candidates = new LinkedHashMap<>();
        matches.forEach(event -> candidates.put(event.observationId(), event));
        runEvents.forEach(event -> candidates.put(event.observationId(), event));

        Set<String> branchIds = ProcessingFlowReconstructor.branchIds(matches);
        List<ObservedEvent> selected = candidates.values().stream()
                .filter(event -> ProcessingFlowReconstructor.belongsToBranch(event, branchIds))
                .toList();
        boolean boundReached = matches.size() == EventObservationService.MAX_LIMIT
                || runEvents.size() == EventObservationService.MAX_LIMIT;
        return ProcessingFlowReconstructor.reconstruct(
                Scope.ITEM, runId, Optional.of(selectedItemId), selected, boundReached);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
