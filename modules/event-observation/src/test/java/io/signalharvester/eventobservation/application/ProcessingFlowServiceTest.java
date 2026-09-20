package io.signalharvester.eventobservation.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.signalharvester.eventobservation.application.ProcessingFlow.Evidence;
import io.signalharvester.eventobservation.application.ProcessingFlow.Limitation;
import io.signalharvester.eventobservation.application.ProcessingFlow.NodeStatus;
import io.signalharvester.eventobservation.application.ProcessingFlow.Stage;
import io.signalharvester.eventobservation.application.ProcessingFlow.State;
import io.signalharvester.eventobservation.model.ObservedEvent;
import io.signalharvester.eventobservation.testing.ObservedEventFixture;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Protects lineage, evidence strength, and completeness semantics in {@link ProcessingFlowService}.
 *
 * <p>Feature: {@code DIAGNOSTICS.PROCESSING_FLOW}.</p>
 */
class ProcessingFlowServiceTest {

    private static final String RUN_ID = "run-1";
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";

    /** Reconstruct an analyzed item through raw publication, derived analysis stages, and an unobserved Results stage. */
    @Test
    void shouldReconstructAnalyzedFlowWithExplicitEvidence() {
        ObservedEvent raw = raw(1L, "raw-event-1", "raw-1", Instant.parse("2026-09-14T10:00:00Z"));
        ObservedEvent analyzed = analyzed(
                2L,
                "analyzed-event-1",
                "raw-event-1",
                "raw-1",
                "norm-1",
                Instant.parse("2026-09-14T10:00:02Z"));
        ProcessingFlowService service = new ProcessingFlowService(new FakeEventQuery(List.of(raw, analyzed), List.of()));

        ProcessingFlow flow = service.collectionRun(RUN_ID);

        assertEquals(State.TERMINAL_EVENT_REACHED, flow.state());
        assertEquals(2, flow.observedEventCount());
        assertEquals(List.of(TRACE_ID), flow.traceIds());
        assertEquals(8, flow.nodes().size());
        assertTrue(flow.limitations().contains(Limitation.RESULTS_PERSISTENCE_NOT_OBSERVED));
        assertEquals(Evidence.NOT_OBSERVED, node(flow, Stage.RESULTS_PERSISTENCE).evidence());
        assertEquals(NodeStatus.PASSED, node(flow, Stage.DEDUPLICATION).status());
        assertEquals("MATCHED", node(flow, Stage.ANALYSIS).outcome().orElseThrow());
        assertEquals(90, node(flow, Stage.ANALYSIS).score().orElseThrow());
        assertTrue(flow.edges().stream().anyMatch(edge -> edge.durationMs().isPresent()
                && edge.durationMs().getAsLong() == 2_000L));
    }

    /** Derive duplicate rejection without claiming that content analysis executed. */
    @Test
    void shouldRepresentDuplicateAsRejectedDeduplicationAndSkippedAnalysis() {
        ObservedEvent raw = raw(1L, "raw-event-1", "raw-1", Instant.parse("2026-09-14T10:00:00Z"));
        ObservedEvent rejected = rejected(
                2L,
                "rejected-event-1",
                "raw-event-1",
                "raw-1",
                "norm-1",
                Instant.parse("2026-09-14T10:00:01Z"));
        ProcessingFlowService service = new ProcessingFlowService(new FakeEventQuery(List.of(raw, rejected), List.of()));

        ProcessingFlow flow = service.collectionRun(RUN_ID);

        assertEquals(State.TERMINAL_EVENT_REACHED, flow.state());
        assertEquals(NodeStatus.REJECTED, node(flow, Stage.DEDUPLICATION).status());
        assertEquals("DUPLICATE", node(flow, Stage.DEDUPLICATION).outcome().orElseThrow());
        assertEquals(NodeStatus.SKIPPED, node(flow, Stage.ANALYSIS).status());
        assertEquals(Evidence.DERIVED_FROM_EVENT, node(flow, Stage.ANALYSIS).evidence());
    }

    /** Keep a raw-only branch visibly in progress while waiting for a terminal Analysis event. */
    @Test
    void shouldRepresentRawOnlyBranchAsInProgress() {
        ObservedEvent raw = raw(1L, "raw-event-1", "raw-1", Instant.parse("2026-09-14T10:00:00Z"));
        ProcessingFlowService service = new ProcessingFlowService(new FakeEventQuery(List.of(raw), List.of()));

        ProcessingFlow flow = service.collectionRun(RUN_ID);

        assertEquals(State.IN_PROGRESS, flow.state());
        assertEquals(3, flow.nodes().size());
        assertFalse(flow.limitations().contains(Limitation.MISSING_RAW_DISCOVERY));
    }

    /** Mark retained terminal history partial when its referenced raw discovery has already disappeared. */
    @Test
    void shouldRepresentMissingRawPredecessorAsPartialHistory() {
        ObservedEvent analyzed = analyzed(
                2L,
                "analyzed-event-1",
                "raw-event-missing",
                "raw-1",
                "norm-1",
                Instant.parse("2026-09-14T10:00:02Z"));
        ProcessingFlowService service = new ProcessingFlowService(new FakeEventQuery(List.of(analyzed), List.of()));

        ProcessingFlow flow = service.collectionRun(RUN_ID);

        assertEquals(State.PARTIAL_HISTORY, flow.state());
        assertTrue(flow.limitations().contains(Limitation.MISSING_RAW_DISCOVERY));
        assertEquals(5, flow.nodes().size());
    }

    /** Mark a graph partial when the bounded Event Observation query reaches its maximum event count. */
    @Test
    void shouldReportHistoryQueryLimitAsPartialHistory() {
        Instant start = Instant.parse("2026-09-14T10:00:00Z");
        List<ObservedEvent> events = new ArrayList<>();
        for (int index = 0; index < EventObservationService.MAX_LIMIT; index++) {
            events.add(raw(index + 1L, "raw-event-" + index, "raw-" + index, start.plusMillis(index)));
        }
        ProcessingFlowService service = new ProcessingFlowService(new FakeEventQuery(events, List.of()));

        ProcessingFlow flow = service.collectionRun(RUN_ID);

        assertEquals(State.PARTIAL_HISTORY, flow.state());
        assertTrue(flow.limitations().contains(Limitation.HISTORY_QUERY_LIMIT_REACHED));
    }

    /** Scope an item graph to the terminal event's source-event branch instead of merging another run branch. */
    @Test
    void shouldScopeNormalizedItemToItsSourceEventBranch() {
        ObservedEvent firstRaw = raw(1L, "raw-event-1", "raw-1", Instant.parse("2026-09-14T10:00:00Z"));
        ObservedEvent firstAnalyzed = analyzed(
                2L,
                "analyzed-event-1",
                "raw-event-1",
                "raw-1",
                "norm-target",
                Instant.parse("2026-09-14T10:00:01Z"));
        ObservedEvent otherRaw = raw(3L, "raw-event-2", "raw-2", Instant.parse("2026-09-14T10:00:02Z"));
        ObservedEvent otherAnalyzed = analyzed(
                4L,
                "analyzed-event-2",
                "raw-event-2",
                "raw-2",
                "norm-other",
                Instant.parse("2026-09-14T10:00:03Z"));
        List<ObservedEvent> runEvents = List.of(firstRaw, firstAnalyzed, otherRaw, otherAnalyzed);
        ProcessingFlowService service = new ProcessingFlowService(
                new FakeEventQuery(runEvents, List.of(firstAnalyzed)));

        ProcessingFlow flow = service.item(RUN_ID, "norm-target");

        assertEquals(ProcessingFlow.Scope.ITEM, flow.scope());
        assertEquals(Optional.of("norm-target"), flow.itemId());
        assertEquals(2, flow.observedEventCount());
        assertTrue(flow.nodes().stream().allMatch(node -> node.branchId().equals("raw-event-1")));
        assertTrue(flow.nodes().stream().noneMatch(node -> node.rawItemId().filter("raw-2"::equals).isPresent()));
    }

    private static ProcessingFlow.Node node(ProcessingFlow flow, Stage stage) {
        return flow.nodes().stream().filter(node -> node.stage() == stage).findFirst().orElseThrow();
    }

    private static ObservedEvent raw(long cursor, String eventId, String rawItemId, Instant occurredAt) {
        return event(
                cursor,
                eventId,
                "collection.raw-item-discovered.v1",
                occurredAt,
                "collection",
                "raw-topic",
                rawItemId,
                "RawItemDiscovered",
                null,
                rawItemId,
                null,
                null,
                null,
                null);
    }

    private static ObservedEvent analyzed(
            long cursor,
            String eventId,
            String sourceEventId,
            String rawItemId,
            String normalizedItemId,
            Instant occurredAt) {
        return event(
                cursor,
                eventId,
                "analysis.item-analyzed.v1",
                occurredAt,
                "analysis",
                "analyzed-topic",
                normalizedItemId,
                "ItemAnalyzed",
                sourceEventId,
                rawItemId,
                normalizedItemId,
                "MATCHED",
                null,
                90);
    }

    private static ObservedEvent rejected(
            long cursor,
            String eventId,
            String sourceEventId,
            String rawItemId,
            String normalizedItemId,
            Instant occurredAt) {
        return event(
                cursor,
                eventId,
                "analysis.item-rejected.v1",
                occurredAt,
                "analysis",
                "rejected-topic",
                normalizedItemId,
                "ItemRejected",
                sourceEventId,
                rawItemId,
                normalizedItemId,
                null,
                "DUPLICATE",
                null);
    }

    private static ObservedEvent event(
            long cursor,
            String eventId,
            String eventType,
            Instant occurredAt,
            String producer,
            String topic,
            String key,
            String payloadType,
            String sourceEventId,
            String rawItemId,
            String normalizedItemId,
            String classification,
            String reasonCode,
            Integer score) {
        ObservedEventFixture.Builder fixture = ObservedEventFixture.observedEvent(cursor, eventId, eventType)
                .occurredAt(occurredAt)
                .observedAt(occurredAt.plusMillis(50))
                .correlationId(RUN_ID)
                .traceparent("00-" + TRACE_ID + "-0123456789abcdef-01")
                .producer(producer)
                .topic(topic)
                .offset(cursor)
                .key(key)
                .payloadType(payloadType)
                .sourceEventId(sourceEventId)
                .rawItemId(rawItemId)
                .normalizedItemId(normalizedItemId)
                .externalId("external-1")
                .title("Senior Java Engineer")
                .url("https://example.test/jobs/1")
                .contentType("text/plain")
                .classification(classification)
                .reasonCode(reasonCode)
                .score(score)
                .explanation(reasonCode != null ? "Already accepted" : "Matched Java");
        if (classification != null) {
            fixture.relevant(true).analyzer("keyword-v1");
        }
        return fixture.build();
    }

    private static final class FakeEventQuery implements EventObservationQuery {
        private final List<ObservedEvent> runEvents;
        private final List<ObservedEvent> itemEvents;

        private FakeEventQuery(List<ObservedEvent> runEvents, List<ObservedEvent> itemEvents) {
            this.runEvents = new ArrayList<>(runEvents);
            this.itemEvents = new ArrayList<>(itemEvents);
        }

        @Override
        public List<ObservedEvent> recent(EventObservationCriteria criteria, int limit) {
            List<ObservedEvent> selected = criteria.itemId().isPresent() ? itemEvents : runEvents;
            return selected.stream().limit(limit).toList();
        }

        @Override
        public long currentCursor() {
            throw new UnsupportedOperationException();
        }

        @Override
        public EventObservationLiveBatch pollAfter(long cursor, EventObservationCriteria criteria, int limit) {
            throw new UnsupportedOperationException();
        }
    }
}
