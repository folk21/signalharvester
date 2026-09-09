package io.signalharvester.configuration.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies a configured external source across configuration and collection boundaries.
 *
 * @param value stable source identifier
 */
public record SourceId(UUID value) {

    public SourceId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * Creates a source identifier from a UUID.
     *
     * @param value source UUID
     * @return source identifier
     */
    public static SourceId of(UUID value) {
        return new SourceId(value);
    }
}
