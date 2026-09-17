package io.signalharvester.analysis.application;

import io.signalharvester.analysis.model.KeywordAnalysisSettings;
import io.signalharvester.analysis.model.NormalizedContentItem;

/** Replaceable application boundary for relevance, classification, and scoring analysis. */
public interface ContentAnalyzer {

    /**
     * Analyzes one normalized, non-duplicate item deterministically with the captured processing settings.
     *
     * @param item normalized content
     * @param settings immutable Analysis settings captured with the source event
     * @return explicit relevance/classification/score result with explanation
     */
    AnalysisDecision analyze(NormalizedContentItem item, KeywordAnalysisSettings settings);
}
