package io.signalharvester.configuration.api;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * Carries validated source configuration input into the configuration module application API.
 *
 * @param name human-readable source name
 * @param type collector type
 * @param location absolute source URI
 * @param enabled whether collection is enabled
 * @param settings source-specific string settings
 */
public record SourceConfigurationCommand(
        String name,
        SourceType type,
        URI location,
        boolean enabled,
        Map<String, String> settings) {

    public SourceConfigurationCommand {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(location, "location");
        settings = Map.copyOf(Objects.requireNonNull(settings, "settings"));
    }

    /**
     * Materializes the public effective configuration for the supplied stable identifier.
     *
     * @param sourceId stable source identifier
     * @return validated configured source
     */
    public ConfiguredSource toConfiguredSource(SourceId sourceId) {
        return new ConfiguredSource(sourceId, name, type, location, enabled, settings);
    }
}
