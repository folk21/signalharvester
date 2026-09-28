package io.signalharvester.operations.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Persisted validated assisted-investigation conclusion tied to one immutable Health Snapshot. */
public record IncidentAssessment(
        UUID id,
        UUID snapshotId,
        Instant createdAt,
        IncidentAssessmentSource source,
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

    public IncidentAssessment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(source, "source");
        provider = requireText(provider, "provider", 128);
        model = requireText(model, "model", 256);
        IncidentAssessmentDraft draft = new IncidentAssessmentDraft(
                summary,
                suspectedSubsystems,
                confidence,
                observations,
                hypotheses,
                evidenceReferences,
                recommendedChecks,
                humanAttentionSuggested);
        summary = draft.summary();
        suspectedSubsystems = draft.suspectedSubsystems();
        confidence = draft.confidence();
        observations = draft.observations();
        hypotheses = draft.hypotheses();
        evidenceReferences = draft.evidenceReferences();
        recommendedChecks = draft.recommendedChecks();
    }

    private static String requireText(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }
}
