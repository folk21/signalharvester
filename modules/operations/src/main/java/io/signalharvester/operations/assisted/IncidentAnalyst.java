package io.signalharvester.operations.assisted;

import io.signalharvester.operations.model.HealthAnalysisPackage;
import io.signalharvester.operations.model.IncidentAssessmentDraft;
import io.signalharvester.operations.assisted.tools.InvestigationToolSession;

/** Provider-neutral boundary for one explicit bounded assisted-investigation request. */
public interface IncidentAnalyst {
    String providerId();
    String modelId();
    IncidentAssessmentDraft analyze(HealthAnalysisPackage analysisPackage);

    /** Runs one bounded investigation. Providers without tool support retain the direct Stage-3 behavior. */
    default IncidentInvestigationResult investigate(
            HealthAnalysisPackage analysisPackage,
            InvestigationToolSession toolSession) {
        return IncidentInvestigationResult.direct(analyze(analysisPackage));
    }
}
