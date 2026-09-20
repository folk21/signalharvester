package io.signalharvester.configuration.application;

import io.micronaut.core.annotation.Introspected;
import io.signalharvester.configuration.api.ConfiguredMonitoringProfile;
import io.signalharvester.configuration.api.MonitoringProfileAnalysisSettings;
import io.signalharvester.configuration.api.MonitoringProfileId;
import io.signalharvester.configuration.api.SourceId;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Input for creating or replacing one monitoring profile. */
@Introspected
public record MonitoringProfileConfigurationCommand(
        @NotBlank String name,
        @NotBlank String informationCategory,
        boolean enabled,
        @Min(MIN_COLLECTION_INTERVAL_MINUTES) int collectionIntervalMinutes,
        @NotEmpty List<@NotNull SourceId> sourceIds,
        @NotNull Map<@NotNull String, @NotNull String> criteria,
        Optional<MonitoringProfileAnalysisSettings> analysisSettings) {

    public static final int MIN_COLLECTION_INTERVAL_MINUTES = 1;

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
