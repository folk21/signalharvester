package io.signalharvester.collection.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Supplies caller-owned context for one explicit collection run.
 *
 * <p>Monitoring profiles are not persisted yet, so the first run slice receives profile/category
 * metadata explicitly while still loading the globally enabled sources through configuration.</p>
 *
 * @param monitoringProfileId logical profile that initiated the run
 * @param informationCategory product-level information category such as JOB or TOPIC
 * @param traceparent W3C traceparent when an upstream tracing boundary supplies one
 */
public record CollectionRunRequest(
        String monitoringProfileId,
        String informationCategory,
        Optional<String> traceparent) {

    public CollectionRunRequest {
        requireNonBlank(monitoringProfileId, "monitoringProfileId");
        requireNonBlank(informationCategory, "informationCategory");
        Objects.requireNonNull(traceparent, "traceparent");
        traceparent.ifPresent(value -> requireNonBlank(value, "traceparent"));
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
