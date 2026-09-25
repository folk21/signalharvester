package io.signalharvester.operations.application;

import io.signalharvester.operations.model.HealthAnomaly;
import io.signalharvester.operations.model.HealthStatus;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Result of one versioned deterministic/statistical health evaluation. */
record HealthEvaluation(
        HealthStatus overallStatus,
        int healthScore,
        Map<String, String> componentStatuses,
        Map<String, Double> signalValues,
        List<HealthAnomaly> anomalies,
        boolean evidenceComplete,
        List<String> unknownReasons) {

    HealthEvaluation {
        Objects.requireNonNull(overallStatus, "overallStatus");
        if (healthScore < 0 || healthScore > 100) {
            throw new IllegalArgumentException("healthScore must be between 0 and 100");
        }
        componentStatuses = Map.copyOf(Objects.requireNonNull(componentStatuses, "componentStatuses"));
        signalValues = Map.copyOf(Objects.requireNonNull(signalValues, "signalValues"));
        anomalies = List.copyOf(Objects.requireNonNull(anomalies, "anomalies"));
        unknownReasons = List.copyOf(Objects.requireNonNull(unknownReasons, "unknownReasons"));
    }
}
