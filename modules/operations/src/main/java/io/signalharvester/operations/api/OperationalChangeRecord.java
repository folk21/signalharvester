package io.signalharvester.operations.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Durable operational change record returned by the change-journal API. */
public record OperationalChangeRecord(
        UUID id,
        Instant changedAt,
        OperationalChangeCategory category,
        OperationalChangeTargetType targetType,
        String targetId,
        Map<String, String> beforeState,
        Map<String, String> afterState,
        OperationalChangeOutcome outcome,
        OperationalChangeSource source,
        String actorId,
        String correlationId,
        String traceId,
        String applicationVersion) {

    public OperationalChangeRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(changedAt, "changedAt");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(beforeState, "beforeState");
        Objects.requireNonNull(afterState, "afterState");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(source, "source");
        targetId = Objects.requireNonNull(targetId, "targetId");
        actorId = Objects.requireNonNull(actorId, "actorId");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        traceId = Objects.requireNonNull(traceId, "traceId");
        applicationVersion = Objects.requireNonNull(applicationVersion, "applicationVersion");
        beforeState = Map.copyOf(beforeState);
        afterState = Map.copyOf(afterState);
    }
}
