package io.signalharvester.eventobservation.application;

import io.signalharvester.eventobservation.application.ProcessingFlow.Edge;
import io.signalharvester.eventobservation.application.ProcessingFlow.EdgeKind;
import io.signalharvester.eventobservation.application.ProcessingFlow.Evidence;
import io.signalharvester.eventobservation.application.ProcessingFlow.KafkaMetadata;
import io.signalharvester.eventobservation.application.ProcessingFlow.Limitation;
import io.signalharvester.eventobservation.application.ProcessingFlow.Node;
import io.signalharvester.eventobservation.application.ProcessingFlow.NodeStatus;
import io.signalharvester.eventobservation.application.ProcessingFlow.Scope;
import io.signalharvester.eventobservation.application.ProcessingFlow.Stage;
import io.signalharvester.eventobservation.application.ProcessingFlow.State;
import io.signalharvester.eventobservation.model.ObservedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

/** Pure reconstruction logic for deterministic processing graphs from retained observation evidence. */
final class ProcessingFlowReconstructor {

    private static final String RAW_PAYLOAD = "RawItemDiscovered";
    private static final String ANALYZED_PAYLOAD = "ItemAnalyzed";
    private static final String REJECTED_PAYLOAD = "ItemRejected";
    private static final String DUPLICATE_REASON = "DUPLICATE";

    private ProcessingFlowReconstructor() {
    }

    static ProcessingFlow reconstruct(
            Scope scope,
            String collectionRunId,
            Optional<String> itemId,
            List<ObservedEvent> input,
            boolean historyQueryLimitReached) {
        List<ObservedEvent> events = input.stream()
                .distinct()
                .sorted(Comparator.comparing(ObservedEvent::occurredAt)
                        .thenComparingLong(ObservedEvent::observationId))
                .toList();

        Map<String, ObservedEvent> rawByEventId = new HashMap<>();
        Map<String, List<ObservedEvent>> terminalsBySourceEventId = new HashMap<>();
        for (ObservedEvent event : events) {
            if (isRaw(event)) {
                rawByEventId.put(event.eventId(), event);
            } else if (isTerminal(event)) {
                String branchId = event.sourceEventId().orElse(event.eventId());
                terminalsBySourceEventId.computeIfAbsent(branchId, ignored -> new ArrayList<>()).add(event);
            }
        }

        Set<String> branchIds = new HashSet<>(rawByEventId.keySet());
        branchIds.addAll(terminalsBySourceEventId.keySet());
        List<String> orderedBranches = branchIds.stream()
                .sorted(Comparator.comparing(
                                (String branchId) -> branchTime(branchId, rawByEventId, terminalsBySourceEventId))
                        .thenComparing(Comparator.naturalOrder()))
                .toList();

        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        EnumSet<Limitation> limitations = EnumSet.noneOf(Limitation.class);
        boolean missingRaw = false;
        boolean missingTerminal = false;
        boolean hasTerminal = false;

        for (String branchId : orderedBranches) {
            ObservedEvent raw = rawByEventId.get(branchId);
            List<ObservedEvent> terminals = terminalsBySourceEventId.getOrDefault(branchId, List.of()).stream()
                    .sorted(Comparator.comparing(ObservedEvent::occurredAt)
                            .thenComparingLong(ObservedEvent::observationId))
                    .toList();

            String previousNodeId = null;
            if (raw != null) {
                String sourceNodeId = nodeId(raw.eventId(), "source");
                String collectionNodeId = nodeId(raw.eventId(), "collection");
                String rawKafkaNodeId = nodeId(raw.eventId(), "raw-kafka");
                nodes.add(sourceNode(raw, sourceNodeId, branchId));
                nodes.add(collectionNode(raw, collectionNodeId, branchId));
                nodes.add(kafkaNode(raw, rawKafkaNodeId, branchId, Stage.RAW_KAFKA));
                edges.add(edge(sourceNodeId, collectionNodeId, EdgeKind.PROCESSING, OptionalLong.empty()));
                edges.add(edge(collectionNodeId, rawKafkaNodeId, EdgeKind.PUBLICATION, OptionalLong.empty()));
                previousNodeId = rawKafkaNodeId;
            } else if (!terminals.isEmpty()) {
                missingRaw = true;
            }

            if (terminals.isEmpty()) {
                if (raw != null) {
                    missingTerminal = true;
                }
                continue;
            }

            hasTerminal = true;
            for (ObservedEvent terminal : terminals) {
                String normalizationNodeId = nodeId(terminal.eventId(), "normalization");
                String deduplicationNodeId = nodeId(terminal.eventId(), "deduplication");
                String analysisNodeId = nodeId(terminal.eventId(), "analysis");
                String terminalKafkaNodeId = nodeId(terminal.eventId(), "terminal-kafka");
                String resultsNodeId = nodeId(terminal.eventId(), "results-persistence");

                nodes.add(normalizationNode(terminal, normalizationNodeId, branchId));
                nodes.add(deduplicationNode(terminal, deduplicationNodeId, branchId));
                nodes.add(analysisNode(terminal, analysisNodeId, branchId));
                nodes.add(kafkaNode(terminal, terminalKafkaNodeId, branchId, Stage.TERMINAL_KAFKA));
                nodes.add(resultsPersistenceNode(terminal, resultsNodeId, branchId));

                if (previousNodeId != null) {
                    edges.add(edge(
                            previousNodeId,
                            normalizationNodeId,
                            EdgeKind.ASYNC_PROCESSING,
                            raw == null ? OptionalLong.empty() : elapsed(raw.occurredAt(), terminal.occurredAt())));
                }
                edges.add(edge(normalizationNodeId, deduplicationNodeId, EdgeKind.PROCESSING, OptionalLong.empty()));
                edges.add(edge(deduplicationNodeId, analysisNodeId, EdgeKind.PROCESSING, OptionalLong.empty()));
                edges.add(edge(analysisNodeId, terminalKafkaNodeId, EdgeKind.PUBLICATION, OptionalLong.empty()));
                edges.add(edge(terminalKafkaNodeId, resultsNodeId, EdgeKind.EXPECTED_PERSISTENCE, OptionalLong.empty()));
                previousNodeId = terminalKafkaNodeId;
            }
        }

        if (historyQueryLimitReached) {
            limitations.add(Limitation.HISTORY_QUERY_LIMIT_REACHED);
        }
        if (missingRaw) {
            limitations.add(Limitation.MISSING_RAW_DISCOVERY);
        }
        if (hasTerminal) {
            limitations.add(Limitation.RESULTS_PERSISTENCE_NOT_OBSERVED);
        }

        State state;
        if (historyQueryLimitReached || missingRaw) {
            state = State.PARTIAL_HISTORY;
        } else if (missingTerminal) {
            state = State.IN_PROGRESS;
        } else {
            state = State.TERMINAL_EVENT_REACHED;
        }

        List<String> traceIds = events.stream()
                .flatMap(event -> event.traceparent().stream())
                .map(ProcessingFlowReconstructor::traceId)
                .flatMap(Optional::stream)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(TreeSet::new), List::copyOf));

        return new ProcessingFlow(
                scope,
                collectionRunId,
                itemId,
                state,
                events.size(),
                traceIds,
                nodes,
                edges,
                List.copyOf(limitations));
    }

    static Set<String> branchIds(List<ObservedEvent> events) {
        Set<String> branchIds = new HashSet<>();
        for (ObservedEvent event : events) {
            if (isRaw(event)) {
                branchIds.add(event.eventId());
            } else if (isTerminal(event)) {
                branchIds.add(event.sourceEventId().orElse(event.eventId()));
            }
        }
        return branchIds;
    }

    static boolean belongsToBranch(ObservedEvent event, Set<String> branchIds) {
        if (isRaw(event)) {
            return branchIds.contains(event.eventId());
        }
        return isTerminal(event) && branchIds.contains(event.sourceEventId().orElse(event.eventId()));
    }

    static EventObservationCriteria criteria(String collectionRunId, Optional<String> itemId) {
        return new EventObservationCriteria(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(collectionRunId),
                itemId,
                Optional.empty());
    }

    private static Instant branchTime(
            String branchId,
            Map<String, ObservedEvent> rawByEventId,
            Map<String, List<ObservedEvent>> terminalsBySourceEventId) {
        ObservedEvent raw = rawByEventId.get(branchId);
        if (raw != null) {
            return raw.occurredAt();
        }
        return terminalsBySourceEventId.getOrDefault(branchId, List.of()).stream()
                .map(ObservedEvent::occurredAt)
                .min(Comparator.naturalOrder())
                .orElse(Instant.EPOCH);
    }

    private static Node sourceNode(ObservedEvent event, String id, String branchId) {
        return node(
                id,
                branchId,
                Stage.EXTERNAL_SOURCE,
                NodeStatus.REACHED,
                Evidence.DERIVED_FROM_EVENT,
                event,
                Optional.empty(),
                OptionalInt.empty(),
                Optional.empty());
    }

    private static Node collectionNode(ObservedEvent event, String id, String branchId) {
        return node(
                id,
                branchId,
                Stage.COLLECTION,
                NodeStatus.COMPLETED,
                Evidence.OBSERVED_EVENT,
                event,
                Optional.empty(),
                OptionalInt.empty(),
                Optional.empty());
    }

    private static Node normalizationNode(ObservedEvent event, String id, String branchId) {
        NodeStatus status = event.normalizedItemId().isPresent() ? NodeStatus.COMPLETED : NodeStatus.UNKNOWN;
        return node(
                id,
                branchId,
                Stage.NORMALIZATION,
                status,
                Evidence.DERIVED_FROM_EVENT,
                event,
                Optional.empty(),
                OptionalInt.empty(),
                Optional.empty());
    }

    private static Node deduplicationNode(ObservedEvent event, String id, String branchId) {
        NodeStatus status;
        Optional<String> outcome;
        if (ANALYZED_PAYLOAD.equals(event.payloadType())) {
            status = NodeStatus.PASSED;
            outcome = Optional.of("UNIQUE");
        } else if (REJECTED_PAYLOAD.equals(event.payloadType())
                && event.reasonCode().filter(DUPLICATE_REASON::equals).isPresent()) {
            status = NodeStatus.REJECTED;
            outcome = event.reasonCode();
        } else {
            status = NodeStatus.UNKNOWN;
            outcome = event.reasonCode();
        }
        return node(
                id,
                branchId,
                Stage.DEDUPLICATION,
                status,
                Evidence.DERIVED_FROM_EVENT,
                event,
                outcome,
                OptionalInt.empty(),
                Optional.empty());
    }

    private static Node analysisNode(ObservedEvent event, String id, String branchId) {
        NodeStatus status;
        Evidence evidence;
        Optional<String> outcome;
        OptionalInt score;
        if (ANALYZED_PAYLOAD.equals(event.payloadType())) {
            status = NodeStatus.COMPLETED;
            evidence = Evidence.OBSERVED_EVENT;
            outcome = event.classification();
            score = event.score();
        } else if (event.reasonCode().filter(DUPLICATE_REASON::equals).isPresent()) {
            status = NodeStatus.SKIPPED;
            evidence = Evidence.DERIVED_FROM_EVENT;
            outcome = event.reasonCode();
            score = OptionalInt.empty();
        } else {
            status = NodeStatus.UNKNOWN;
            evidence = Evidence.DERIVED_FROM_EVENT;
            outcome = event.reasonCode();
            score = OptionalInt.empty();
        }
        return node(id, branchId, Stage.ANALYSIS, status, evidence, event, outcome, score, Optional.empty());
    }

    private static Node kafkaNode(ObservedEvent event, String id, String branchId, Stage stage) {
        return node(
                id,
                branchId,
                stage,
                NodeStatus.PUBLISHED,
                Evidence.OBSERVED_KAFKA_METADATA,
                event,
                Optional.empty(),
                OptionalInt.empty(),
                Optional.of(new KafkaMetadata(
                        event.kafkaTopic(), event.kafkaPartition(), event.kafkaOffset(), event.kafkaKey())));
    }

    private static Node resultsPersistenceNode(ObservedEvent event, String id, String branchId) {
        return new Node(
                id,
                branchId,
                Stage.RESULTS_PERSISTENCE,
                NodeStatus.UNKNOWN,
                Evidence.NOT_OBSERVED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                event.traceparent(),
                event.sourceEventId(),
                event.rawItemId(),
                event.normalizedItemId(),
                event.sourceId(),
                event.monitoringProfileId(),
                Optional.empty(),
                OptionalInt.empty(),
                Optional.empty());
    }

    private static Node node(
            String id,
            String branchId,
            Stage stage,
            NodeStatus status,
            Evidence evidence,
            ObservedEvent event,
            Optional<String> outcome,
            OptionalInt score,
            Optional<KafkaMetadata> kafka) {
        return new Node(
                id,
                branchId,
                stage,
                status,
                evidence,
                Optional.of(event.occurredAt()),
                Optional.of(event.eventId()),
                Optional.of(event.eventType()),
                Optional.of(event.producer()),
                event.traceparent(),
                event.sourceEventId(),
                event.rawItemId(),
                event.normalizedItemId(),
                event.sourceId(),
                event.monitoringProfileId(),
                outcome,
                score,
                kafka);
    }

    private static Edge edge(String from, String to, EdgeKind kind, OptionalLong durationMs) {
        return new Edge(from, to, kind, durationMs);
    }

    private static OptionalLong elapsed(Instant from, Instant to) {
        long millis = Duration.between(from, to).toMillis();
        return millis < 0 ? OptionalLong.empty() : OptionalLong.of(millis);
    }

    private static Optional<String> traceId(String traceparent) {
        String[] parts = traceparent.split("-", -1);
        if (parts.length != 4 || parts[1].length() != 32) {
            return Optional.empty();
        }
        return Optional.of(parts[1]);
    }

    private static boolean isRaw(ObservedEvent event) {
        return RAW_PAYLOAD.equals(event.payloadType());
    }

    private static boolean isTerminal(ObservedEvent event) {
        return ANALYZED_PAYLOAD.equals(event.payloadType()) || REJECTED_PAYLOAD.equals(event.payloadType());
    }

    private static String nodeId(String eventId, String suffix) {
        return "event:" + eventId + ":" + suffix;
    }
}
