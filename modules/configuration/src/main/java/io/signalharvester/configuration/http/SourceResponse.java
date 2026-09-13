package io.signalharvester.configuration.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceType;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a configured source in the public REST API without exposing persistence types.
 *
 * @param id stable source identifier
 * @param name human-readable source name
 * @param type source collector type
 * @param location absolute HTTP(S) source URI
 * @param enabled whether collection is enabled
 * @param settings source-specific string settings
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable
public record SourceResponse(
        UUID id,
        String name,
        SourceType type,
        String location,
        boolean enabled,
        Map<String, String> settings) {

    /**
     * Maps effective configuration to the public REST representation.
     *
     * @param source effective configured source
     * @return REST response
     */
    public static SourceResponse from(ConfiguredSource source) {
        return new SourceResponse(
                source.id().value(),
                source.name(),
                source.type(),
                source.location().toString(),
                source.enabled(),
                source.settings());
    }
}
