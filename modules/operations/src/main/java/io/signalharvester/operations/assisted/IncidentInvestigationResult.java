package io.signalharvester.operations.assisted;

import io.signalharvester.operations.model.IncidentAssessmentDraft;
import java.util.Objects;

/** Structured provider result plus bounded investigation accounting. */
public record IncidentInvestigationResult(
        IncidentAssessmentDraft assessment,
        int toolCallCount,
        int roundCount) {

    public IncidentInvestigationResult {
        Objects.requireNonNull(assessment, "assessment");
        if (toolCallCount < 0) {
            throw new IllegalArgumentException("toolCallCount must not be negative");
        }
        if (roundCount < 1) {
            throw new IllegalArgumentException("roundCount must be at least 1");
        }
    }

    public static IncidentInvestigationResult direct(IncidentAssessmentDraft assessment) {
        return new IncidentInvestigationResult(assessment, 0, 1);
    }
}
