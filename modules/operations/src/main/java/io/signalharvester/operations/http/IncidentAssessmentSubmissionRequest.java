package io.signalharvester.operations.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.operations.model.IncidentAssessmentDraft;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Structured bounded assessment pasted back after manual LLM/human analysis of an exported package. */
@Serdeable
public record IncidentAssessmentSubmissionRequest(
        @NotNull UUID snapshotId,
        @NotBlank @Size(max = 4000) String summary,
        @NotNull @Size(max = 16) List<@NotBlank @Size(max = 128) String> suspectedSubsystems,
        @DecimalMin("0.0") @DecimalMax("1.0") double confidence,
        @NotNull @Size(max = 32) List<@NotBlank @Size(max = 1000) String> observations,
        @NotNull @Size(max = 32) List<@NotBlank @Size(max = 1000) String> hypotheses,
        @NotNull @Size(max = 64) List<@NotBlank @Size(max = 256) String> evidenceReferences,
        @NotNull @Size(max = 32) List<@NotBlank @Size(max = 1000) String> recommendedChecks,
        boolean humanAttentionSuggested) {

    IncidentAssessmentDraft toDraft() {
        return new IncidentAssessmentDraft(
                summary,
                suspectedSubsystems,
                confidence,
                observations,
                hypotheses,
                evidenceReferences,
                recommendedChecks,
                humanAttentionSuggested);
    }
}
