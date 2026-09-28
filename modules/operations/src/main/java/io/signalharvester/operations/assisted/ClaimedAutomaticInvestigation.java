package io.signalharvester.operations.assisted;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Exact-token lease for one automatic provider investigation claimed by a backend replica. */
public record ClaimedAutomaticInvestigation(
        UUID triggerId,
        UUID snapshotId,
        AutomaticInvestigationTriggerType type,
        int attemptCount,
        UUID leaseToken,
        Instant leaseExpiresAt) {

    public ClaimedAutomaticInvestigation {
        Objects.requireNonNull(triggerId, "triggerId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(leaseToken, "leaseToken");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        if (attemptCount < 1) {
            throw new IllegalArgumentException("attemptCount must be positive for a claimed trigger");
        }
    }
}
