package io.signalharvester.configuration.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Effective persisted monitoring-profile configuration exposed to backend consumers. */
public record ConfiguredMonitoringProfile(
        MonitoringProfileId id,
        String name,
        String informationCategory,
        boolean enabled,
        int collectionIntervalMinutes,
        List<SourceId> sourceIds,
        Map<String, String> criteria,
        MonitoringProfileAnalysisSettings analysisSettings) {

    public ConfiguredMonitoringProfile {
        Objects.requireNonNull(id, "id");
        name = requireText(name, "name");
        informationCategory = requireText(informationCategory, "informationCategory");
        if (collectionIntervalMinutes < 1) {
            throw new IllegalArgumentException("collectionIntervalMinutes must be positive");
        }
        sourceIds = List.copyOf(Objects.requireNonNull(sourceIds, "sourceIds"));
        if (sourceIds.isEmpty()) {
            throw new IllegalArgumentException("sourceIds must not be empty");
        }
        if (sourceIds.stream().distinct().count() != sourceIds.size()) {
            throw new IllegalArgumentException("sourceIds must not contain duplicates");
        }
        criteria = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(criteria, "criteria")));
        Objects.requireNonNull(analysisSettings, "analysisSettings");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }
}
