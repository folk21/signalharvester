package io.signalharvester.collection.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Protects low-cardinality metrics for feature {@code OBSERVABILITY.APPLICATION}. */
class CollectionObservabilityTest {

    /** Record collection run and source-fetch outcomes without entity identifiers as labels. */
    @Test
    void shouldRecordCollectionMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CollectionObservability observability = new CollectionObservability(Optional.of(registry), Optional.empty());

        observability.recordRun("SUCCEEDED", Duration.ofMillis(12));
        observability.recordSourceFetch("success", Duration.ofMillis(4));

        assertEquals(1.0, registry.get("signalharvester.collection.runs")
                .tag("status", "SUCCEEDED").counter().count());
        assertEquals(1L, registry.get("signalharvester.collection.run.duration")
                .tag("status", "SUCCEEDED").timer().count());
        assertEquals(1.0, registry.get("signalharvester.collection.source.fetches")
                .tag("outcome", "success").counter().count());
    }
}
