package io.signalharvester.configuration.api;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * Provides the effective external-source configuration exposed to other backend modules.
 *
 * <p>The record intentionally contains only configuration required at the module boundary.
 * Source-specific settings remain string values until a concrete collector requires a stronger
 * typed contract.</p>
 *
 * @param id stable source identifier
 * @param name human-readable source name
 * @param type reusable collector type
 * @param location absolute external source location
 * @param enabled whether collection is enabled for the source
 * @param settings source-specific configuration values
 */
public record ConfiguredSource(
        SourceId id,
        String name,
        SourceType type,
        URI location,
        boolean enabled,
        Map<String, String> settings) {

    public ConfiguredSource {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(settings, "settings");

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (!location.isAbsolute()) {
            throw new IllegalArgumentException("location must be absolute");
        }

        settings = Map.copyOf(settings);
    }
}
