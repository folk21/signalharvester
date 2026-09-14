package io.signalharvester.configuration.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.application.MonitoringProfileConfigurationCommand;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** REST payload for creating or replacing a monitoring profile. */
@Serdeable
public record MonitoringProfileUpsertRequest(
        @NotBlank String name,
        @NotBlank String informationCategory,
        boolean enabled,
        @Min(1) int collectionIntervalMinutes,
        @NotEmpty List<@NotNull UUID> sourceIds,
        Map<@NotNull String, @NotNull String> criteria) {

    MonitoringProfileConfigurationCommand toCommand() {
        return new MonitoringProfileConfigurationCommand(
                name,
                informationCategory,
                enabled,
                collectionIntervalMinutes,
                sourceIds.stream().map(SourceId::of).toList(),
                criteria == null ? Map.of() : Map.copyOf(criteria));
    }
}
