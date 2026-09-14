package io.signalharvester.collection.scheduling;

import io.signalharvester.configuration.api.MonitoringProfileId;
import java.util.Objects;
import java.util.UUID;

/** Identifies one exclusive scheduled-run lease for a monitoring profile. */
public record ProfileScheduleLease(
        MonitoringProfileId profileId,
        UUID token,
        int intervalMinutes) {

    public ProfileScheduleLease {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(token, "token");
        if (intervalMinutes < 1) {
            throw new IllegalArgumentException("intervalMinutes must be positive");
        }
    }
}
