package io.signalharvester.eventobservation.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** Deterministic application-level processing graph reconstructed from observed technical events. */
public record ProcessingFlow(
        Scope scope,
        String collectionRunId,
        Optional<String> itemId,
        State state,
        int observedEventCount,
        List<String> traceIds,
        List<Node> nodes,
        List<Edge> edges,
        List<Limitation> limitations) {

    public ProcessingFlow {
        Objects.requireNonNull(scope, "scope");
        collectionRunId = requireNonBlank(collectionRunId, "collectionRunId");
        itemId = Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(state, "state");
        if (observedEventCount <= 0) {
            throw new IllegalArgumentException("observedEventCount must be positive");
        }
        traceIds = List.copyOf(Objects.requireNonNull(traceIds, "traceIds"));
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
    }

    /** Scope selected by the Event Explorer. */
    public enum Scope {
        COLLECTION_RUN,
        ITEM
    }

    /** Completeness of the currently observable event lineage. */
    public enum State {
        TERMINAL_EVENT_REACHED,
        IN_PROGRESS,
        PARTIAL_HISTORY
    }

    /** Stage represented by one graph node. */
    public enum Stage {
        EXTERNAL_SOURCE,
        COLLECTION,
        RAW_KAFKA,
        NORMALIZATION,
        DEDUPLICATION,
        ANALYSIS,
        TERMINAL_KAFKA,
        RESULTS_PERSISTENCE
    }

    /** Status represented by a stage node. */
    public enum NodeStatus {
        REACHED,
        COMPLETED,
        PUBLISHED,
        PASSED,
        REJECTED,
        SKIPPED,
        UNKNOWN
    }

    /** Evidence level behind a reconstructed stage. */
    public enum Evidence {
        OBSERVED_EVENT,
        OBSERVED_KAFKA_METADATA,
        DERIVED_FROM_EVENT,
        NOT_OBSERVED
    }

    /** Relationship between two graph stages. */
    public enum EdgeKind {
        PROCESSING,
        PUBLICATION,
        ASYNC_PROCESSING,
        EXPECTED_PERSISTENCE
    }

    /** Explicit reason that a reconstructed graph may not contain end-to-end evidence. */
    public enum Limitation {
        HISTORY_QUERY_LIMIT_REACHED,
        MISSING_RAW_DISCOVERY,
        RESULTS_PERSISTENCE_NOT_OBSERVED
    }

    /** Kafka position captured with an observed event. */
    public record KafkaMetadata(String topic, int partition, long offset, String key) {
        public KafkaMetadata {
            topic = requireNonBlank(topic, "topic");
            if (partition < 0) {
                throw new IllegalArgumentException("partition must not be negative");
            }
            if (offset < 0) {
                throw new IllegalArgumentException("offset must not be negative");
            }
            key = requireNonBlank(key, "key");
        }
    }

    /** One deterministic stage node in a reconstructed item branch. */
    public record Node(
            String id,
            String branchId,
            Stage stage,
            NodeStatus status,
            Evidence evidence,
            Optional<Instant> occurredAt,
            Optional<String> eventId,
            Optional<String> eventType,
            Optional<String> producer,
            Optional<String> traceparent,
            Optional<String> sourceEventId,
            Optional<String> rawItemId,
            Optional<String> normalizedItemId,
            Optional<String> sourceId,
            Optional<String> monitoringProfileId,
            Optional<String> outcome,
            OptionalInt score,
            Optional<KafkaMetadata> kafka) {

        public Node {
            id = requireNonBlank(id, "id");
            branchId = requireNonBlank(branchId, "branchId");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(evidence, "evidence");
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
            eventId = Objects.requireNonNull(eventId, "eventId");
            eventType = Objects.requireNonNull(eventType, "eventType");
            producer = Objects.requireNonNull(producer, "producer");
            traceparent = Objects.requireNonNull(traceparent, "traceparent");
            sourceEventId = Objects.requireNonNull(sourceEventId, "sourceEventId");
            rawItemId = Objects.requireNonNull(rawItemId, "rawItemId");
            normalizedItemId = Objects.requireNonNull(normalizedItemId, "normalizedItemId");
            sourceId = Objects.requireNonNull(sourceId, "sourceId");
            monitoringProfileId = Objects.requireNonNull(monitoringProfileId, "monitoringProfileId");
            outcome = Objects.requireNonNull(outcome, "outcome");
            score = Objects.requireNonNull(score, "score");
            kafka = Objects.requireNonNull(kafka, "kafka");
        }
    }

    /** Directed relation between reconstructed stages. */
    public record Edge(String from, String to, EdgeKind kind, OptionalLong durationMs) {
        public Edge {
            from = requireNonBlank(from, "from");
            to = requireNonBlank(to, "to");
            Objects.requireNonNull(kind, "kind");
            durationMs = Objects.requireNonNull(durationMs, "durationMs");
            if (durationMs.isPresent() && durationMs.getAsLong() < 0) {
                throw new IllegalArgumentException("durationMs must not be negative");
            }
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
