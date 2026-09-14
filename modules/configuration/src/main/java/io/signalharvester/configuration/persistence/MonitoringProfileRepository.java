package io.signalharvester.configuration.persistence;

import io.signalharvester.configuration.api.ConfiguredMonitoringProfile;
import io.signalharvester.configuration.api.MonitoringProfileId;
import io.signalharvester.configuration.api.SourceId;
import java.util.List;
import java.util.Optional;

/** Internal persistence boundary for monitoring profiles. */
public interface MonitoringProfileRepository {
    List<ConfiguredMonitoringProfile> findAll();
    List<ConfiguredMonitoringProfile> findEnabled();
    Optional<ConfiguredMonitoringProfile> findById(MonitoringProfileId profileId);
    void insert(ConfiguredMonitoringProfile profile);
    boolean update(ConfiguredMonitoringProfile profile);
    boolean delete(MonitoringProfileId profileId);
    boolean referencesSource(SourceId sourceId);
}
