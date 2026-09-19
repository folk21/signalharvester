package io.signalharvester.configuration.application;

import io.micronaut.core.annotation.Introspected;
import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.api.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Carries validated source configuration input into the configuration module application API.
 *
 * @param name human-readable source name
 * @param type collector type
 * @param location absolute source URI
 * @param enabled whether collection is enabled
 * @param settings source-specific string settings
 */
@Introspected
public record SourceConfigurationCommand(
        @NotBlank String name,
        @NotNull SourceType type,
        @NotNull URI location,
        boolean enabled,
        @NotNull Map<@NotNull String, @NotNull String> settings) {

    public SourceConfigurationCommand {
        settings = settings == null
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
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
