package io.signalharvester.configuration.application;

import io.signalharvester.configuration.api.ConfiguredMonitoringProfile;
import io.signalharvester.configuration.api.MonitoringProfileAnalysisSettings;
import io.signalharvester.configuration.api.MonitoringProfileId;
import io.signalharvester.configuration.api.SourceId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Input for creating or replacing one monitoring profile. */
public record MonitoringProfileConfigurationCommand(
        String name,
        String informationCategory,
        boolean enabled,
        int collectionIntervalMinutes,
        List<SourceId> sourceIds,
        Map<String, String> criteria,
        Optional<MonitoringProfileAnalysisSettings> analysisSettings) {

    public MonitoringProfileConfigurationCommand {
        analysisSettings = analysisSettings == null ? Optional.empty() : analysisSettings;
    }

    /** Compatibility constructor for internal callers that do not yet provide typed Analysis settings. */
    public MonitoringProfileConfigurationCommand(
            String name,
            String informationCategory,
            boolean enabled,
            int collectionIntervalMinutes,
            List<SourceId> sourceIds,
            Map<String, String> criteria) {
        this(name, informationCategory, enabled, collectionIntervalMinutes, sourceIds, criteria, Optional.empty());
    }

    ConfiguredMonitoringProfile toProfile(
            MonitoringProfileId id,
            MonitoringProfileAnalysisSettings effectiveAnalysisSettings) {
        return new ConfiguredMonitoringProfile(
                id,
                name,
                informationCategory,
                enabled,
                collectionIntervalMinutes,
                sourceIds,
                criteria,
                effectiveAnalysisSettings);
    }
}
