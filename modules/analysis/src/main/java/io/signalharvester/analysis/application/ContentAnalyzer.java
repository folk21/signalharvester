package io.signalharvester.analysis.application;

import io.signalharvester.analysis.model.NormalizedContentItem;

/**
 * Replaceable application boundary for relevance, classification, and scoring analysis.
 */
public interface ContentAnalyzer {

    /**
     * Analyzes one normalized, non-duplicate item deterministically for the active implementation.
     *
     * @param item normalized content
     * @return explicit relevance/classification/score result with explanation
     */
    AnalysisDecision analyze(NormalizedContentItem item);
}
