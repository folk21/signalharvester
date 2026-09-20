package io.signalharvester.collection.scheduling;

import io.signalharvester.configuration.api.MonitoringProfileId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Persistence port for collection-owned monitoring-profile schedule state. */
public interface ProfileScheduleStateStore {
    Optional<ProfileScheduleLease> claimIfDue(
            MonitoringProfileId profileId,
            int intervalMinutes,
            Instant now,
            Duration leaseDuration);

    boolean renew(ProfileScheduleLease lease, Instant now, Duration leaseDuration);

    boolean release(ProfileScheduleLease lease, Instant releasedAt);

    boolean complete(ProfileScheduleLease lease, Instant completedAt);
}
