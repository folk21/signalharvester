package io.signalharvester.configuration.application;

import io.signalharvester.configuration.api.ConfiguredMonitoringProfile;
import io.signalharvester.configuration.api.MonitoringProfileId;
import java.util.List;

/** Internal application boundary for monitoring-profile administration. */
public interface MonitoringProfileConfigurationOperations {
    ConfiguredMonitoringProfile create(MonitoringProfileConfigurationCommand command);
    List<ConfiguredMonitoringProfile> list();
    ConfiguredMonitoringProfile get(MonitoringProfileId profileId);
    ConfiguredMonitoringProfile update(MonitoringProfileId profileId, MonitoringProfileConfigurationCommand command);
    void delete(MonitoringProfileId profileId);
}
