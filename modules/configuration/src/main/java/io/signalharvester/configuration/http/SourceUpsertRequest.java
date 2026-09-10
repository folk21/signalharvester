package io.signalharvester.configuration.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.configuration.api.SourceType;
import io.signalharvester.configuration.application.InvalidSourceConfigurationException;
import io.signalharvester.configuration.application.SourceConfigurationCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents the external REST payload used to create or replace a configured source.
 *
 * @param name human-readable source name
 * @param type source collector type
 * @param location absolute HTTP(S) source URI
 * @param enabled whether collection is enabled
 * @param settings source-specific string settings
 */
@Serdeable
public record SourceUpsertRequest(
        @NotBlank String name,
        @NotNull SourceType type,
        @NotBlank @ValidSourceLocation String location,
        boolean enabled,
        Map<@NotNull String, @NotNull String> settings) {

    public SourceUpsertRequest {
        settings = settings == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    }

    /**
     * Maps the validated HTTP payload to the configuration application command.
     *
     * @return source configuration command
     */
    public SourceConfigurationCommand toCommand() {
        try {
            return new SourceConfigurationCommand(name, type, URI.create(location), enabled, settings);
        } catch (IllegalArgumentException exception) {
            throw new InvalidSourceConfigurationException("Invalid source location", exception);
        }
    }
}
