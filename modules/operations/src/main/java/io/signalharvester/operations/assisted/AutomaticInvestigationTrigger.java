package io.signalharvester.operations.assisted;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persisted automatic investigation trigger used for deduplication, cooldown, and operator inspection. */
public record AutomaticInvestigationTrigger(
        UUID id,
        UUID snapshotId,
        AutomaticInvestigationTriggerType type,
        AutomaticInvestigationTriggerState state,
        Instant createdAt,
        Instant nextAttemptAt,
        int attemptCount,
        Instant completedAt,
        UUID assessmentId,
        String lastError) {

    public AutomaticInvestigationTrigger {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount must not be negative");
        }
    }

    /** Returns true while this trigger still owns or awaits provider work. */
    public boolean active() {
        return state == AutomaticInvestigationTriggerState.PENDING
                || state == AutomaticInvestigationTriggerState.CLAIMED;
    }
}
