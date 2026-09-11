package io.signalharvester.analysis.rules;

import io.signalharvester.analysis.application.AnalysisDecision;
import io.signalharvester.analysis.application.ContentAnalyzer;
import io.signalharvester.analysis.configuration.KeywordAnalysisConfiguration;
import io.signalharvester.analysis.model.NormalizedContentItem;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic non-AI analyzer that classifies content from configurable keyword matches.
 *
 * <p>The rule set is deliberately global only for the first vertical slice. Persistent monitoring
 * profiles will later own per-profile analysis settings behind the same {@link ContentAnalyzer} boundary.</p>
 */
@Singleton
public final class KeywordContentAnalyzer implements ContentAnalyzer {

    static final String ANALYZER_ID = "keyword-v1";
    static final String MATCHED_CLASSIFICATION = "MATCHED_KEYWORDS";
    static final String UNMATCHED_CLASSIFICATION = "NO_KEYWORD_MATCH";

    private final List<String> keywords;
    private final int minimumMatches;

    public KeywordContentAnalyzer(KeywordAnalysisConfiguration configuration) {
        this(configuration.getKeywords(), configuration.getMinimumMatches());
    }

    KeywordContentAnalyzer(List<String> keywords, int minimumMatches) {
        Objects.requireNonNull(keywords, "keywords");
        if (minimumMatches < 1) {
            throw new IllegalArgumentException("minimumMatches must be positive");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                throw new IllegalArgumentException("keywords must not contain blank values");
            }
            normalized.add(keyword.strip().toLowerCase(Locale.ROOT));
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("keywords must not be empty");
        }
        if (minimumMatches > normalized.size()) {
            throw new IllegalArgumentException("minimumMatches must not exceed the number of unique keywords");
        }
        this.keywords = List.copyOf(normalized);
        this.minimumMatches = minimumMatches;
    }

    @Override
    public AnalysisDecision analyze(NormalizedContentItem item) {
        Objects.requireNonNull(item, "item");
        String title = item.title().orElse("");
        String haystack = (title + "\n" + item.content()).toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        for (String keyword : keywords) {
            if (haystack.contains(keyword)) {
                matched.add(keyword);
            }
        }

        boolean relevant = matched.size() >= minimumMatches;
        int score = (int) Math.round((matched.size() * 100.0) / keywords.size());
        String classification = relevant ? MATCHED_CLASSIFICATION : UNMATCHED_CLASSIFICATION;
        String explanation = matched.isEmpty()
                ? "No configured keywords matched"
                : "Matched " + matched.size() + " of " + keywords.size()
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
