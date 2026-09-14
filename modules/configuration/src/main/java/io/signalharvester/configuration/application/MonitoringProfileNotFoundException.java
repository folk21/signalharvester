package io.signalharvester.configuration.application;

import io.signalharvester.configuration.api.MonitoringProfileId;

/** Signals that a requested monitoring profile does not exist. */
public final class MonitoringProfileNotFoundException extends RuntimeException {
    public MonitoringProfileNotFoundException(MonitoringProfileId profileId) {
        super("Monitoring profile not found: " + profileId.value());
    }
}
