package io.signalharvester.operations.assisted;

import io.signalharvester.operations.model.HealthAnalysisPackage;
import io.signalharvester.operations.model.IncidentAssessmentDraft;

/** Provider-neutral boundary for one explicit bounded assisted-investigation request. */
public interface IncidentAnalyst {
    String providerId();
    String modelId();
    IncidentAssessmentDraft analyze(HealthAnalysisPackage analysisPackage);
}
