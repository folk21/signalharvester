package io.signalharvester.configuration.api;

import java.util.List;
import java.util.Optional;

/** Exposes effective monitoring-profile configuration to backend consumers. */
public interface MonitoringProfileConfigurationProvider {
    Optional<ConfiguredMonitoringProfile> findProfile(MonitoringProfileId profileId);

    List<ConfiguredMonitoringProfile> findEnabledProfiles();
}
