package io.signalharvester.operations.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.operations.model.HealthSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** REST representation of one persisted Health Snapshot. */
@Serdeable
public record HealthSnapshotResponse(
        UUID id,
        Instant generatedAt,
        Instant windowStartedAt,
        Instant windowEndedAt,
        String overallStatus,
        int healthScore,
        String policyVersion,
        Map<String, String> componentStatuses,
        Map<String, Double> signalValues,
        List<String> anomalyCandidates,
        List<HealthAnomalyResponse> anomalies,
        List<UUID> recentChangeIds,
        String applicationVersion,
        boolean evidenceComplete,
        List<String> unknownReasons) {

    static HealthSnapshotResponse from(HealthSnapshot snapshot) {
        return new HealthSnapshotResponse(
                snapshot.id(), snapshot.generatedAt(), snapshot.windowStartedAt(), snapshot.windowEndedAt(),
                snapshot.overallStatus().name(), snapshot.healthScore(), snapshot.policyVersion(),
                snapshot.componentStatuses(), snapshot.signalValues(), snapshot.anomalyCandidates(),
                snapshot.anomalyDetails().stream().map(HealthAnomalyResponse::from).toList(),
                snapshot.recentChangeIds(), snapshot.applicationVersion(), snapshot.evidenceComplete(),
                snapshot.unknownReasons());
    }
}
