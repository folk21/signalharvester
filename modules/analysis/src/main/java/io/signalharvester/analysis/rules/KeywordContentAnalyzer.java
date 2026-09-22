package io.signalharvester.analysis.rules;

import io.signalharvester.analysis.application.AnalysisDecision;
import io.signalharvester.analysis.application.ContentAnalyzer;
import io.signalharvester.analysis.model.KeywordAnalysisSettings;
import io.signalharvester.analysis.model.NormalizedContentItem;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Deterministic stateless analyzer that classifies content from captured keyword settings. */
@Singleton
public final class KeywordContentAnalyzer implements ContentAnalyzer {

    static final String ANALYZER_ID = "keyword-v1";
    static final String MATCHED_CLASSIFICATION = "MATCHED_KEYWORDS";
    static final String UNMATCHED_CLASSIFICATION = "NO_KEYWORD_MATCH";
    static final String ALL_RELEVANT_CLASSIFICATION = "ALL_RELEVANT";

    @Override
    public AnalysisDecision analyze(NormalizedContentItem item, KeywordAnalysisSettings settings) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(settings, "settings");
        if (settings.isAllRelevant()) {
            return new AnalysisDecision(
                    true,
                    ALL_RELEVANT_CLASSIFICATION,
                    100,
                    List.of(),
                    "No keyword filter configured; all analyzed items are relevant",
                    ANALYZER_ID);
        }
        String title = item.title().orElse("");
        String haystack = (title + "\n" + item.content()).toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        for (String keyword : settings.keywords()) {
            if (haystack.contains(keyword)) {
                matched.add(keyword);
            }
        }

        boolean relevant = matched.size() >= settings.minimumMatches();
        int score = (int) Math.round((matched.size() * 100.0) / settings.keywords().size());
        String classification = relevant ? MATCHED_CLASSIFICATION : UNMATCHED_CLASSIFICATION;
        String explanation = matched.isEmpty()
                ? "No configured keywords matched"
                : "Matched " + matched.size() + " of " + settings.keywords().size()
                        + " configured keywords: " + String.join(", ", matched);
        return new AnalysisDecision(
                relevant,
                classification,
                score,
                matched,
                explanation,
                ANALYZER_ID);
    }
}
