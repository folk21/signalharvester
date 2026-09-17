package io.signalharvester.configuration.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.configuration.api.ConfiguredMonitoringProfile;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** REST representation of one persisted monitoring profile. */
@Serdeable
public record MonitoringProfileResponse(
        UUID id,
        String name,
        String informationCategory,
        boolean enabled,
        int collectionIntervalMinutes,
        List<UUID> sourceIds,
        Map<String, String> criteria,
        MonitoringProfileAnalysisSettingsResponse analysisSettings) {

    static MonitoringProfileResponse from(ConfiguredMonitoringProfile profile) {
        return new MonitoringProfileResponse(
                profile.id().value(),
                profile.name(),
                profile.informationCategory(),
                profile.enabled(),
                profile.collectionIntervalMinutes(),
                profile.sourceIds().stream().map(sourceId -> sourceId.value()).toList(),
                profile.criteria(),
                MonitoringProfileAnalysisSettingsResponse.from(profile.analysisSettings()));
    }
}
