package io.signalharvester.operations.model;

import java.util.Objects;

/** Structured bounded anomaly evidence produced by the deterministic/statistical Health Engine. */
public record HealthAnomaly(
        String signal,
        String detector,
        HealthStatus severity,
        double observedValue,
        Double referenceValue,
        double anomalyScore,
        String evidence) {

    public HealthAnomaly {
        signal = requireText(signal, "signal");
        detector = requireText(detector, "detector");
        Objects.requireNonNull(severity, "severity");
        if (severity != HealthStatus.DEGRADED && severity != HealthStatus.UNHEALTHY) {
            throw new IllegalArgumentException("anomaly severity must be DEGRADED or UNHEALTHY");
        }
        if (!Double.isFinite(observedValue)) {
            throw new IllegalArgumentException("observedValue must be finite");
        }
        if (referenceValue != null && !Double.isFinite(referenceValue)) {
            throw new IllegalArgumentException("referenceValue must be finite when present");
        }
        if (!Double.isFinite(anomalyScore) || anomalyScore < 0.0 || anomalyScore > 1.0) {
            throw new IllegalArgumentException("anomalyScore must be between 0 and 1");
        }
        evidence = requireText(evidence, "evidence");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }
}
