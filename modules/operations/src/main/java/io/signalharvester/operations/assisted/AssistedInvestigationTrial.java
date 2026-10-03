package io.signalharvester.operations.assisted;

import io.signalharvester.operations.model.IncidentAssessment;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Completed provider investigation captured as bounded evaluation evidence for one scenario-linked Health Snapshot. */
public record AssistedInvestigationTrial(
        UUID trialId,
        String scenarioRunId,
        UUID snapshotId,
        Instant generatedAt,
        IncidentAssessment assessment,
        int toolCallCount,
        int roundCount,
        long durationMs,
        List<String> allowedEvidenceReferences,
        List<String> discoveredEvidenceReferences,
        int maxToolCalls,
        int maxRounds,
        long maxInvestigationDurationMs) {

    public AssistedInvestigationTrial {
        Objects.requireNonNull(trialId, "trialId");
        scenarioRunId = requireText(scenarioRunId, "scenarioRunId", 256);
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(assessment, "assessment");
        if (!assessment.snapshotId().equals(snapshotId)) {
            throw new IllegalArgumentException("assessment snapshotId must match trial snapshotId");
        }
        if (toolCallCount < 0) {
            throw new IllegalArgumentException("toolCallCount must not be negative");
        }
        if (roundCount < 1) {
            throw new IllegalArgumentException("roundCount must be at least 1");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
        allowedEvidenceReferences = List.copyOf(
                Objects.requireNonNull(allowedEvidenceReferences, "allowedEvidenceReferences"));
        discoveredEvidenceReferences = List.copyOf(
                Objects.requireNonNull(discoveredEvidenceReferences, "discoveredEvidenceReferences"));
        if (maxToolCalls < 0) {
            throw new IllegalArgumentException("maxToolCalls must not be negative");
        }
        if (maxRounds < 1) {
            throw new IllegalArgumentException("maxRounds must be at least 1");
        }
        if (maxInvestigationDurationMs < 1) {
            throw new IllegalArgumentException("maxInvestigationDurationMs must be positive");
        }
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
