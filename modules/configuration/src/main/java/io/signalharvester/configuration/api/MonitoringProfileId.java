package io.signalharvester.configuration.api;

import java.util.Objects;
import java.util.UUID;

/** Stable identity of one persisted monitoring profile. */
public record MonitoringProfileId(UUID value) {
    public MonitoringProfileId {
        Objects.requireNonNull(value, "value");
    }

    public static MonitoringProfileId of(UUID value) {
        return new MonitoringProfileId(value);
    }
}
