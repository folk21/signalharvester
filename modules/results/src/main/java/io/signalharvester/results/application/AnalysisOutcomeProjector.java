package io.signalharvester.results.application;

import io.signalharvester.results.model.AnalyzedResult;
import io.signalharvester.results.model.RejectedResult;

/**
 * Internal application boundary for materializing terminal analysis events into Results-owned state.
 */
public interface AnalysisOutcomeProjector {

    void projectAnalyzed(AnalyzedResult result);

    void projectRejected(RejectedResult result);
}
