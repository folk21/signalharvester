package io.signalharvester.analysis.event;

import io.signalharvester.analysis.application.AnalysisDecision;
import io.signalharvester.analysis.model.NormalizedContentItem;
import java.util.Objects;

/**
 * Analysis-owned semantic payload ready for publication as an ItemAnalyzed integration event.
 */
public record AnalyzedItem(NormalizedContentItem item, AnalysisDecision decision) {

    public AnalyzedItem {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(decision, "decision");
    }
}
