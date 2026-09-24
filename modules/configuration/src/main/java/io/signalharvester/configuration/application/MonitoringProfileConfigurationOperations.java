package io.signalharvester.configuration.application;

import io.signalharvester.configuration.api.ConfiguredMonitoringProfile;
import io.signalharvester.configuration.api.MonitoringProfileId;
import io.signalharvester.operations.api.OperationalChangeContext;
import java.util.List;

/** Internal application boundary for monitoring-profile administration. */
public interface MonitoringProfileConfigurationOperations {
    default ConfiguredMonitoringProfile create(MonitoringProfileConfigurationCommand command) {
        return create(command, OperationalChangeContext.untrackedSystem());
    }
    ConfiguredMonitoringProfile create(
            MonitoringProfileConfigurationCommand command,
            OperationalChangeContext changeContext);
    List<ConfiguredMonitoringProfile> list();
    ConfiguredMonitoringProfile get(MonitoringProfileId profileId);
    default ConfiguredMonitoringProfile update(
            MonitoringProfileId profileId,
            MonitoringProfileConfigurationCommand command) {
        return update(profileId, command, OperationalChangeContext.untrackedSystem());
    }
    ConfiguredMonitoringProfile update(
            MonitoringProfileId profileId,
            MonitoringProfileConfigurationCommand command,
            OperationalChangeContext changeContext);
    default void delete(MonitoringProfileId profileId) {
        delete(profileId, OperationalChangeContext.untrackedSystem());
    }
    void delete(MonitoringProfileId profileId, OperationalChangeContext changeContext);
}
