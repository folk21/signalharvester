package io.signalharvester.configuration.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.configuration.api.MonitoringProfileAnalysisSettings;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** REST input for deterministic Analysis settings owned by one monitoring profile. */
@Serdeable
public record MonitoringProfileAnalysisSettingsRequest(
        @NotNull List<@NotBlank @NotNull String> keywords,
        @Min(0) int minimumMatches) {

    MonitoringProfileAnalysisSettings toSettings() {
        return new MonitoringProfileAnalysisSettings(keywords, minimumMatches);
    }
}
