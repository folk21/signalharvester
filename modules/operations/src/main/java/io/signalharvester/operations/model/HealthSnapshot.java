package io.signalharvester.operations.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Persisted bounded operational health evidence for one analyzed time window. */
public record HealthSnapshot(
        UUID id,
        Instant generatedAt,
        Instant windowStartedAt,
        Instant windowEndedAt,
        HealthStatus overallStatus,
        int healthScore,
        String policyVersion,
        Map<String, String> componentStatuses,
        Map<String, Double> signalValues,
        List<String> anomalyCandidates,
        List<UUID> recentChangeIds,
        String applicationVersion,
        boolean evidenceComplete,
        List<String> unknownReasons) {

    public HealthSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(windowStartedAt, "windowStartedAt");
        Objects.requireNonNull(windowEndedAt, "windowEndedAt");
        Objects.requireNonNull(overallStatus, "overallStatus");
        if (healthScore < 0 || healthScore > 100) {
            throw new IllegalArgumentException("healthScore must be between 0 and 100");
        }
        policyVersion = requireText(policyVersion, "policyVersion");
        componentStatuses = Map.copyOf(Objects.requireNonNull(componentStatuses, "componentStatuses"));
        signalValues = Map.copyOf(Objects.requireNonNull(signalValues, "signalValues"));
        anomalyCandidates = List.copyOf(Objects.requireNonNull(anomalyCandidates, "anomalyCandidates"));
        recentChangeIds = List.copyOf(Objects.requireNonNull(recentChangeIds, "recentChangeIds"));
        applicationVersion = requireText(applicationVersion, "applicationVersion");
        unknownReasons = List.copyOf(Objects.requireNonNull(unknownReasons, "unknownReasons"));
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
