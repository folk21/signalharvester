package io.signalharvester.operations.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.signalharvester.operations.model.HealthSnapshot;
import io.signalharvester.operations.model.HealthStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies explainable deterministic/statistical health scoring without external telemetry services. */
class DeterministicStatisticalHealthEngineTest {

    @Test
    void shouldReportHealthyWhenRequiredSignalsArePresentAndWithinPolicy() {
        try (ApplicationContext context = ApplicationContext.run()) {
            var engine = new DeterministicStatisticalHealthEngine(context.getBean(HealthPolicyConfiguration.class));
            Map<String, Double> signals = requiredSignals(0.0, 0.0, 0.0, 0.0);
            var evaluation = engine.evaluate(new HealthSignalEvidence(signals, List.of()), baseline(signals, 5));

            assertEquals(HealthStatus.HEALTHY, evaluation.overallStatus());
            assertEquals(100, evaluation.healthScore());
            assertTrue(evaluation.anomalies().isEmpty());
            assertTrue(evaluation.evidenceComplete());
        }
    }

    @Test
    void shouldApplyConfiguredHardThresholds() {
        try (ApplicationContext context = ApplicationContext.run()) {
            var engine = new DeterministicStatisticalHealthEngine(context.getBean(HealthPolicyConfiguration.class));
            Map<String, Double> signals = requiredSignals(0.0, 0.0, 300.0, 0.0);
            var evaluation = engine.evaluate(new HealthSignalEvidence(signals, List.of()), baseline(requiredSignals(0, 0, 0, 0), 5));

            assertEquals(HealthStatus.DEGRADED, evaluation.overallStatus());
            assertEquals(85, evaluation.healthScore());
            assertEquals("hard-threshold", evaluation.anomalies().getFirst().detector());
            assertEquals(DeterministicStatisticalHealthEngine.OUTBOX_PENDING, evaluation.anomalies().getFirst().signal());
        }
    }

    @Test
    void shouldDetectRollingMedianMadDeviationBelowHardThreshold() {
        try (ApplicationContext context = ApplicationContext.run()) {
            var engine = new DeterministicStatisticalHealthEngine(context.getBean(HealthPolicyConfiguration.class));
            Map<String, Double> current = requiredSignals(0.0, 300.0, 0.0, 0.0);
            List<HealthSnapshot> history = List.of(
                    baselineSnapshot(requiredSignals(0, 100, 0, 0), 1),
                    baselineSnapshot(requiredSignals(0, 105, 0, 0), 2),
                    baselineSnapshot(requiredSignals(0, 95, 0, 0), 3),
                    baselineSnapshot(requiredSignals(0, 110, 0, 0), 4),
                    baselineSnapshot(requiredSignals(0, 90, 0, 0), 5));

            var evaluation = engine.evaluate(new HealthSignalEvidence(current, List.of()), history);

            assertEquals(HealthStatus.UNHEALTHY, evaluation.overallStatus());
            assertEquals("rolling-median-mad", evaluation.anomalies().getFirst().detector());
            assertEquals(DeterministicStatisticalHealthEngine.KAFKA_CONSUMER_LAG, evaluation.anomalies().getFirst().signal());
            assertTrue(evaluation.anomalies().getFirst().anomalyScore() > 0.9);
        }
    }

    @Test
    void shouldRemainUnknownWhenRequiredSignalsAreUnavailable() {
        try (ApplicationContext context = ApplicationContext.run()) {
            var engine = new DeterministicStatisticalHealthEngine(context.getBean(HealthPolicyConfiguration.class));
            var evaluation = engine.evaluate(
                    new HealthSignalEvidence(Map.of(), List.of("prometheus-query-failed:kafka.consumerLag")),
                    List.of());

            assertEquals(HealthStatus.UNKNOWN, evaluation.overallStatus());
            assertEquals(0, evaluation.healthScore());
            assertFalse(evaluation.evidenceComplete());
            assertTrue(evaluation.unknownReasons().stream().anyMatch(value -> value.startsWith("required-signal-unavailable:")));
        }
    }

    private static Map<String, Double> requiredSignals(
            double unavailableReplicas,
            double kafkaLag,
            double pending,
            double oldestAgeSeconds) {
        return Map.of(
                DeterministicStatisticalHealthEngine.BACKEND_UNAVAILABLE_REPLICAS, unavailableReplicas,
                DeterministicStatisticalHealthEngine.KAFKA_CONSUMER_LAG, kafkaLag,
                DeterministicStatisticalHealthEngine.OUTBOX_PENDING, pending,
                DeterministicStatisticalHealthEngine.OUTBOX_OLDEST_PENDING_AGE_SECONDS, oldestAgeSeconds);
    }

    private static List<HealthSnapshot> baseline(Map<String, Double> signals, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> baselineSnapshot(signals, index + 1))
                .toList();
    }

    private static HealthSnapshot baselineSnapshot(Map<String, Double> signals, int second) {
        Instant instant = Instant.parse("2026-09-24T12:00:00Z").plusSeconds(second);
        return new HealthSnapshot(
                UUID.randomUUID(),
                instant,
                instant.minusSeconds(300),
                instant,
                HealthStatus.HEALTHY,
                100,
                "test-policy",
                Map.of(),
                signals,
                List.of(),
                List.of(),
                List.of(),
                "test",
                true,
                List.of());
    }
}
