package io.signalharvester.operations.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.operations.assisted.AssistedInvestigationTrial;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Versioned single-trial artifact consumed by the offline assisted-investigation evaluator. */
@Serdeable
public record AssistedInvestigationEvidenceResponse(
        int schemaVersion,
        String artifactType,
        Instant generatedAt,
        Source source,
        Budgets budgets,
        List<Trial> trials) {

    private static final int SCHEMA_VERSION = 1;
    private static final String ARTIFACT_TYPE = "signalharvester-assisted-investigation-evidence";
    private static final String RUNNER = "signalharvester-runtime";

    /** Maps one completed runtime capture to the evaluator's version-1 artifact contract. */
    public static AssistedInvestigationEvidenceResponse from(AssistedInvestigationTrial value) {
        var assessment = IncidentAssessmentResponse.from(value.assessment());
        return new AssistedInvestigationEvidenceResponse(
                SCHEMA_VERSION,
                ARTIFACT_TYPE,
                value.generatedAt(),
                new Source(RUNNER, assessment.provider(), assessment.model()),
                new Budgets(value.maxToolCalls(), value.maxRounds(), value.maxInvestigationDurationMs()),
                List.of(new Trial(
                        value.trialId(),
                        value.scenarioRunId(),
                        value.snapshotId(),
                        "COMPLETED",
                        new Execution(value.toolCallCount(), value.roundCount(), value.durationMs()),
                        value.allowedEvidenceReferences(),
                        value.discoveredEvidenceReferences(),
                        assessment,
                        List.of())));
    }

    /** Runtime identity recorded once for the single exported trial. */
    @Serdeable
    public record Source(String runner, String provider, String model) {}

    /** Application-owned investigation limits that governed the exported provider invocation. */
    @Serdeable
    public record Budgets(int maxToolCalls, int maxRounds, long maxInvestigationDurationMs) {}

    /** One completed scenario-linked runtime investigation. */
    @Serdeable
    public record Trial(
            UUID trialId,
            String scenarioRunId,
            UUID snapshotId,
            String status,
            Execution execution,
            List<String> allowedEvidenceReferences,
            List<String> discoveredEvidenceReferences,
            IncidentAssessmentResponse assessment,
            List<Map<String, Object>> claimAnnotations) {}

    /** Runtime execution counters captured around the provider/tool interaction. */
    @Serdeable
    public record Execution(int toolCallCount, int roundCount, long durationMs) {}
}
