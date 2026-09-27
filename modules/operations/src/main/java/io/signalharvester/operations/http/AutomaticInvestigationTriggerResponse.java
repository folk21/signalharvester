package io.signalharvester.operations.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.operations.assisted.AutomaticInvestigationTrigger;
import java.time.Instant;
import java.util.UUID;

/** REST representation of one durable automatic assisted-investigation trigger. */
@Serdeable
public record AutomaticInvestigationTriggerResponse(
        UUID id,
        UUID snapshotId,
        String type,
        String state,
        Instant createdAt,
        Instant nextAttemptAt,
        int attemptCount,
        Instant completedAt,
        UUID assessmentId,
        String lastError) {

    static AutomaticInvestigationTriggerResponse from(AutomaticInvestigationTrigger value) {
        return new AutomaticInvestigationTriggerResponse(
                value.id(),
                value.snapshotId(),
                value.type().name(),
                value.state().name(),
                value.createdAt(),
                value.nextAttemptAt(),
                value.attemptCount(),
                value.completedAt(),
                value.assessmentId(),
                value.lastError());
    }
}
