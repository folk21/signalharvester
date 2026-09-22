package io.signalharvester.configuration.api;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Immutable deterministic Analysis settings owned by one monitoring profile. */
public record MonitoringProfileAnalysisSettings(List<String> keywords, int minimumMatches) {

    public MonitoringProfileAnalysisSettings {
        Objects.requireNonNull(keywords, "keywords");
        Set<String> normalized = new LinkedHashSet<>();
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                throw new IllegalArgumentException("keywords must not contain blank values");
            }
            normalized.add(keyword.strip().toLowerCase(Locale.ROOT));
        }
        if (normalized.isEmpty()) {
            if (minimumMatches != 0) {
                throw new IllegalArgumentException("minimumMatches must be zero when no keywords are configured");
            }
        } else {
            if (minimumMatches < 1) {
                throw new IllegalArgumentException("minimumMatches must be positive when keywords are configured");
            }
            if (minimumMatches > normalized.size()) {
                throw new IllegalArgumentException("minimumMatches must not exceed the number of unique keywords");
            }
        }
        keywords = List.copyOf(normalized);
    }

    /** Returns settings that classify every analyzed item as relevant. */
    public static MonitoringProfileAnalysisSettings allRelevant() {
        return new MonitoringProfileAnalysisSettings(List.of(), 0);
    }

    /** Returns whether this profile intentionally bypasses keyword filtering. */
    public boolean isAllRelevant() {
        return keywords.isEmpty();
    }
}
