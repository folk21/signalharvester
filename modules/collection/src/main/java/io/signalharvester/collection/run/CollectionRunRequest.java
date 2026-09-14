package io.signalharvester.collection.run;

import io.signalharvester.configuration.api.MonitoringProfileId;
import java.util.Objects;
import java.util.Optional;

/**
 * Identifies one persisted monitoring profile to collect.
 *
 * @param monitoringProfileId persisted profile that owns category and source membership
 * @param traceparent W3C traceparent when an upstream tracing boundary supplies one
 */
public record CollectionRunRequest(
        MonitoringProfileId monitoringProfileId,
        Optional<String> traceparent) {

    public CollectionRunRequest {
        Objects.requireNonNull(monitoringProfileId, "monitoringProfileId");
        Objects.requireNonNull(traceparent, "traceparent");
        traceparent.ifPresent(value -> {
            if (value.isBlank()) {
                throw new IllegalArgumentException("traceparent must not be blank");
            }
        });
    }
}
