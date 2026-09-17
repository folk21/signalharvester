package io.signalharvester.configuration.api;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Immutable deterministic keyword-analysis settings owned by one monitoring profile. */
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
            throw new IllegalArgumentException("keywords must not be empty");
        }
        if (minimumMatches < 1) {
            throw new IllegalArgumentException("minimumMatches must be positive");
        }
        if (minimumMatches > normalized.size()) {
            throw new IllegalArgumentException("minimumMatches must not exceed the number of unique keywords");
        }
        keywords = List.copyOf(normalized);
    }
}
