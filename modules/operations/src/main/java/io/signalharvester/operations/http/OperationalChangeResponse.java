package io.signalharvester.operations.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.operations.api.OperationalChangeRecord;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** REST representation of one sanitized operational change record. */
@Serdeable
public record OperationalChangeResponse(
        UUID id,
        Instant changedAt,
        String category,
        String targetType,
        String targetId,
        Map<String, String> beforeState,
        Map<String, String> afterState,
        String outcome,
        String source,
        String actorId,
        String correlationId,
        String traceId,
        String applicationVersion) {

    static OperationalChangeResponse from(OperationalChangeRecord change) {
        return new OperationalChangeResponse(
                change.id(), change.changedAt(), change.category().name(), change.targetType().name(),
                change.targetId(), change.beforeState(), change.afterState(), change.outcome().name(),
                change.source().name(), change.actorId(), change.correlationId(), change.traceId(),
                change.applicationVersion());
    }
}
