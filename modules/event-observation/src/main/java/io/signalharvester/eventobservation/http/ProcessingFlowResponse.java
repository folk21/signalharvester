package io.signalharvester.eventobservation.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.eventobservation.application.ProcessingFlow;
import java.time.Instant;
import java.util.List;

/** Browser-facing deterministic processing graph for one collection run or scoped item. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable
public record ProcessingFlowResponse(
        String scope,
        String collectionRunId,
        @Nullable String itemId,
        String state,
        int observedEventCount,
        List<String> traceIds,
        List<Node> nodes,
        List<Edge> edges,
        List<String> limitations) {

    static ProcessingFlowResponse from(ProcessingFlow flow) {
        return new ProcessingFlowResponse(
                flow.scope().name(),
                flow.collectionRunId(),
                flow.itemId().orElse(null),
                flow.state().name(),
                flow.observedEventCount(),
                flow.traceIds(),
                flow.nodes().stream().map(Node::from).toList(),
                flow.edges().stream().map(Edge::from).toList(),
                flow.limitations().stream().map(Enum::name).toList());
    }

    /** One processing-stage node with explicit evidence strength. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Serdeable
    public record Node(
            String id,
            String branchId,
            String stage,
            String status,
            String evidence,
            @Nullable Instant occurredAt,
            @Nullable String eventId,
            @Nullable String eventType,
            @Nullable String producer,
            @Nullable String traceparent,
            @Nullable String sourceEventId,
            @Nullable String rawItemId,
            @Nullable String normalizedItemId,
            @Nullable String sourceId,
            @Nullable String monitoringProfileId,
            @Nullable String outcome,
            @Nullable Integer score,
            @Nullable KafkaMetadata kafka) {

        static Node from(ProcessingFlow.Node node) {
            return new Node(
                    node.id(),
                    node.branchId(),
                    node.stage().name(),
                    node.status().name(),
                    node.evidence().name(),
                    node.occurredAt().orElse(null),
                    node.eventId().orElse(null),
                    node.eventType().orElse(null),
                    node.producer().orElse(null),
                    node.traceparent().orElse(null),
                    node.sourceEventId().orElse(null),
                    node.rawItemId().orElse(null),
                    node.normalizedItemId().orElse(null),
                    node.sourceId().orElse(null),
                    node.monitoringProfileId().orElse(null),
                    node.outcome().orElse(null),
                    node.score().isPresent() ? node.score().getAsInt() : null,
                    node.kafka().map(KafkaMetadata::from).orElse(null));
        }
    }

    /** Kafka transport position attached to an observed flow stage. */
    @Serdeable
    public record KafkaMetadata(String topic, int partition, long offset, String key) {
        static KafkaMetadata from(ProcessingFlow.KafkaMetadata kafka) {
            return new KafkaMetadata(kafka.topic(), kafka.partition(), kafka.offset(), kafka.key());
        }
    }

    /** Directed edge between two processing stages. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Serdeable
    public record Edge(String from, String to, String kind, @Nullable Long durationMs) {
        static Edge from(ProcessingFlow.Edge edge) {
            return new Edge(
                    edge.from(),
                    edge.to(),
                    edge.kind().name(),
                    edge.durationMs().isPresent() ? edge.durationMs().getAsLong() : null);
        }
    }
}
