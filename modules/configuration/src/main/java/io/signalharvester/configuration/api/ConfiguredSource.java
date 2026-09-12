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
 * @param location absolute HTTP or HTTPS source location without embedded credentials or fragments
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
        if (!"http".equalsIgnoreCase(location.getScheme())
                && !"https".equalsIgnoreCase(location.getScheme())) {
            throw new IllegalArgumentException("location must use http or https");
        }
        if (location.getHost() == null || location.getHost().isBlank()) {
            throw new IllegalArgumentException("location must contain a host");
        }
        if (location.getUserInfo() != null) {
            throw new IllegalArgumentException("location must not contain embedded credentials");
        }
        if (location.getFragment() != null) {
            throw new IllegalArgumentException("location must not contain a fragment");
        }

        settings = Map.copyOf(settings);
    }
}
