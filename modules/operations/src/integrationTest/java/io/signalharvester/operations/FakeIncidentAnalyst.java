package io.signalharvester.operations;

import io.micronaut.context.annotation.Requires;
import io.signalharvester.operations.assisted.IncidentAnalyst;
import io.signalharvester.operations.assisted.IncidentInvestigationResult;
import io.signalharvester.operations.assisted.tools.InvestigationToolName;
import io.signalharvester.operations.assisted.tools.InvestigationToolRequest;
import io.signalharvester.operations.assisted.tools.InvestigationToolSession;
import io.signalharvester.operations.model.HealthAnalysisPackage;
import io.signalharvester.operations.model.IncidentAssessmentDraft;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;

/** Deterministic offline analyst used by Operations integration tests. */
@Singleton
@Requires(property = "spec.name", value = "operations-assisted-investigation")
final class FakeIncidentAnalyst implements IncidentAnalyst {
    @Override
    public String providerId() {
        return "fake";
    }

    @Override
    public String modelId() {
        return "deterministic-test-model";
    }

    @Override
    public IncidentAssessmentDraft analyze(HealthAnalysisPackage analysisPackage) {
        return directAssessment(analysisPackage);
    }

    @Override
    public IncidentInvestigationResult investigate(
            HealthAnalysisPackage analysisPackage,
            InvestigationToolSession toolSession) {
        var toolResult = toolSession.execute(new InvestigationToolRequest(
                "fake-health-context-1",
                InvestigationToolName.HEALTH_CONTEXT,
                Map.of()));
        String snapshotReference = "health-snapshot:" + analysisPackage.snapshotId();
        String discoveredReference = toolResult.evidenceReferences().stream()
                .filter(reference -> reference.startsWith("health-snapshot:") && !reference.equals(snapshotReference))
                .findFirst()
                .orElse(snapshotReference);
        return new IncidentInvestigationResult(
                new IncidentAssessmentDraft(
                        "The deterministic Health Snapshot is incomplete and requires operator review.",
                        List.of("operations"),
                        0.75,
                        List.of("The persisted snapshot reports incomplete evidence.",
                                "A bounded read-only health-context tool call completed successfully."),
                        List.of("Prometheus evidence may be unavailable in this isolated test environment."),
                        List.of(discoveredReference),
                        List.of("Verify the configured Prometheus endpoint before drawing a causal conclusion."),
                        true),
                toolSession.toolCallCount(),
                2);
    }

    private static IncidentAssessmentDraft directAssessment(HealthAnalysisPackage analysisPackage) {
        return new IncidentAssessmentDraft(
                "The deterministic Health Snapshot is incomplete and requires operator review.",
                List.of("operations"),
                0.75,
                List.of("The persisted snapshot reports incomplete evidence."),
                List.of("Prometheus evidence may be unavailable in this isolated test environment."),
                List.of("health-snapshot:" + analysisPackage.snapshotId()),
                List.of("Verify the configured Prometheus endpoint before drawing a causal conclusion."),
                true);
    }
}
