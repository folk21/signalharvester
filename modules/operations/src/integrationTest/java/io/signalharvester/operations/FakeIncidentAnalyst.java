package io.signalharvester.operations;

import io.micronaut.context.annotation.Requires;
import io.signalharvester.operations.assisted.IncidentAnalyst;
import io.signalharvester.operations.model.HealthAnalysisPackage;
import io.signalharvester.operations.model.IncidentAssessmentDraft;
import jakarta.inject.Singleton;
import java.util.List;

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
