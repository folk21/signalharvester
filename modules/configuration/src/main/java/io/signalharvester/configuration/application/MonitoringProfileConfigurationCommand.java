package io.signalharvester.configuration.application;

import io.signalharvester.configuration.api.ConfiguredMonitoringProfile;
import io.signalharvester.configuration.api.MonitoringProfileId;
import io.signalharvester.configuration.api.SourceId;
import java.util.List;
import java.util.Map;

/** Input for creating or replacing one monitoring profile. */
public record MonitoringProfileConfigurationCommand(
        String name,
        String informationCategory,
        boolean enabled,
        int collectionIntervalMinutes,
        List<SourceId> sourceIds,
        Map<String, String> criteria) {

    ConfiguredMonitoringProfile toProfile(MonitoringProfileId id) {
        return new ConfiguredMonitoringProfile(
                id, name, informationCategory, enabled, collectionIntervalMinutes, sourceIds, criteria);
    }
}
