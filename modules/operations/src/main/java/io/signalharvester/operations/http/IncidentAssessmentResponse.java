package io.signalharvester.operations.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.operations.model.IncidentAssessment;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** REST representation of one persisted validated incident assessment. */
@Serdeable
public record IncidentAssessmentResponse(
        UUID id,
        UUID snapshotId,
        Instant createdAt,
        String source,
        String provider,
        String model,
        String summary,
        List<String> suspectedSubsystems,
        double confidence,
        List<String> observations,
        List<String> hypotheses,
        List<String> evidenceReferences,
        List<String> recommendedChecks,
        boolean humanAttentionSuggested) {

    static IncidentAssessmentResponse from(IncidentAssessment value) {
        return new IncidentAssessmentResponse(
                value.id(),
                value.snapshotId(),
                value.createdAt(),
                value.source().name(),
                value.provider(),
                value.model(),
                value.summary(),
                value.suspectedSubsystems(),
                value.confidence(),
                value.observations(),
                value.hypotheses(),
                value.evidenceReferences(),
                value.recommendedChecks(),
                value.humanAttentionSuggested());
    }
}
