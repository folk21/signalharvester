package io.signalharvester.security.model;

import java.util.Objects;
import java.util.UUID;

/** Stable persisted identity for one SignalHarvester account. */
public record UserId(UUID value) {
    public UserId {
        Objects.requireNonNull(value, "value");
    }

    /** Creates a user identifier from its UUID value. */
    public static UserId of(UUID value) {
        return new UserId(value);
    }
}
