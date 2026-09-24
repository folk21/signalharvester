package io.signalharvester.analysis.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Protects low-cardinality Analysis metrics for feature {@code OBSERVABILITY.APPLICATION}. */
class AnalysisObservabilityTest {

    /** Record processing and outbox outcomes through the optional metrics boundary. */
    @Test
    void shouldRecordAnalysisMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AnalysisObservability observability = new AnalysisObservability(Optional.of(registry), Optional.empty());

        observability.recordProcessing("ANALYZED", Duration.ofMillis(8));
        observability.recordOutboxPublication("published");
        observability.updateOutboxBacklog(3, Duration.ofSeconds(7));
        observability.recordOutboxBatch(2, Duration.ofMillis(12));
        observability.recordOutboxKafkaPublish("success", Duration.ofMillis(4));
        observability.recordOutboxDatabaseOperation("claim", "success", Duration.ofMillis(3));

        assertEquals(1.0, registry.get("signalharvester.analysis.items")
                .tag("status", "ANALYZED").counter().count());
        assertEquals(1L, registry.get("signalharvester.analysis.processing.duration")
                .tag("status", "ANALYZED").timer().count());
        assertEquals(1.0, registry.get("signalharvester.analysis.outbox.publications")
                .tag("outcome", "published").counter().count());
        assertEquals(3.0, registry.get("signalharvester.analysis.outbox.pending").gauge().value());
        assertEquals(7.0, registry.get("signalharvester.analysis.outbox.oldest.pending.age").gauge().value());
        assertEquals(1L, registry.get("signalharvester.analysis.outbox.batch.size").summary().count());
        assertEquals(2.0, registry.get("signalharvester.analysis.outbox.batch.size").summary().totalAmount());
        assertEquals(1L, registry.get("signalharvester.analysis.outbox.batch.duration").timer().count());
        assertEquals(1L, registry.get("signalharvester.analysis.outbox.kafka.publish.duration")
                .tag("outcome", "success").timer().count());
        assertEquals(1L, registry.get("signalharvester.analysis.outbox.database.operation.duration")
                .tag("operation", "claim")
                .tag("outcome", "success")
                .timer()
                .count());
    }
}
