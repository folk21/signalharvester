package io.signalharvester.operations.alert;

import io.signalharvester.operations.model.HealthStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persisted application-owned human-attention alert with self-contained deterministic evidence. */
public record HumanAttentionAlert(
        UUID id,
        HumanAttentionAlertState state,
        HumanAttentionAlertSeverity severity,
        HumanAttentionAlertReason reason,
        String policyVersion,
        Instant openedAt,
        Instant lastObservedAt,
        Instant resolvedAt,
        UUID firstSnapshotId,
        UUID latestSnapshotId,
        HealthStatus latestHealthStatus,
        int latestHealthScore,
        UUID latestAssessmentId,
        Boolean modelAttentionSuggested) {

    public HumanAttentionAlert {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(reason, "reason");
        policyVersion = requireText(policyVersion, "policyVersion");
        Objects.requireNonNull(openedAt, "openedAt");
        Objects.requireNonNull(lastObservedAt, "lastObservedAt");
        Objects.requireNonNull(firstSnapshotId, "firstSnapshotId");
        Objects.requireNonNull(latestSnapshotId, "latestSnapshotId");
        Objects.requireNonNull(latestHealthStatus, "latestHealthStatus");
        if (latestHealthScore < 0 || latestHealthScore > 100) {
            throw new IllegalArgumentException("latestHealthScore must be between 0 and 100");
        }
        if (state == HumanAttentionAlertState.OPEN && resolvedAt != null) {
            throw new IllegalArgumentException("OPEN alert must not have resolvedAt");
        }
        if (state == HumanAttentionAlertState.RESOLVED && resolvedAt == null) {
            throw new IllegalArgumentException("RESOLVED alert must have resolvedAt");
        }
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
