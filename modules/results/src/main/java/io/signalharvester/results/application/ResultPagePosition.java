package io.signalharvester.results.application;

import java.time.Instant;

/** Decoded deterministic Results sort key used internally for keyset continuation. */
public record ResultPagePosition(Instant analyzedAt, String monitoringProfileId, String normalizedItemId) {

    public ResultPagePosition {
        if (analyzedAt == null) {
            throw new IllegalArgumentException("analyzedAt must not be null");
        }
        if (monitoringProfileId == null || monitoringProfileId.isBlank()) {
            throw new IllegalArgumentException("monitoringProfileId must not be blank");
        }
        if (normalizedItemId == null || !normalizedItemId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("normalizedItemId must contain 64 lowercase hexadecimal characters");
        }
    }
}
