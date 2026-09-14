package io.signalharvester.collection.run;

import io.signalharvester.configuration.api.MonitoringProfileId;

/** Raised when collection is requested for a monitoring profile that no longer exists. */
public final class CollectionProfileNotFoundException extends RuntimeException {
    public CollectionProfileNotFoundException(MonitoringProfileId profileId) {
        super("Monitoring profile not found: " + profileId.value());
    }
}
