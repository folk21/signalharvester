package io.signalharvester.analysis.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Snapshot of unpublished Analysis outbox rows used for operational telemetry. */
public record AnalysisOutboxBacklog(long pendingCount, Optional<Instant> oldestCreatedAt) {

    public AnalysisOutboxBacklog {
        if (pendingCount < 0) {
            throw new IllegalArgumentException("pendingCount must not be negative");
        }
        oldestCreatedAt = Objects.requireNonNull(oldestCreatedAt, "oldestCreatedAt");
        if (pendingCount == 0 && oldestCreatedAt.isPresent()) {
            throw new IllegalArgumentException("oldestCreatedAt must be empty when pendingCount is zero");
        }
        if (pendingCount > 0 && oldestCreatedAt.isEmpty()) {
            throw new IllegalArgumentException("oldestCreatedAt is required when pendingCount is positive");
        }
    }
}
