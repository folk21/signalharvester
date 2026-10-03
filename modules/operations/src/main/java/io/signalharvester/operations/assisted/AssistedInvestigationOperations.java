package io.signalharvester.operations.assisted;

import io.signalharvester.operations.model.HealthAnalysisPackage;
import io.signalharvester.operations.model.IncidentAssessment;
import io.signalharvester.operations.model.IncidentAssessmentDraft;
import java.util.List;
import java.util.UUID;

/** Internal application boundary for manual and explicitly invoked provider-assisted investigation. */
public interface AssistedInvestigationOperations {
    HealthAnalysisPackage latestAnalysisPackage();
    IncidentAssessment submitManual(UUID snapshotId, IncidentAssessmentDraft draft);
    IncidentAssessment analyzeLatest();

    /** Runs and persists one provider assessment for a scenario-linked snapshot and returns bounded evaluation metadata. */
    AssistedInvestigationTrial captureEvaluationTrial(String scenarioRunId, UUID snapshotId);
    List<IncidentAssessment> recentAssessments(int limit);
}
