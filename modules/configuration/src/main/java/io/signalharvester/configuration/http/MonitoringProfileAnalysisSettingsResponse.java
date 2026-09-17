package io.signalharvester.configuration.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.configuration.api.MonitoringProfileAnalysisSettings;
import java.util.List;

/** REST representation of effective deterministic Analysis settings for one monitoring profile. */
@Serdeable
public record MonitoringProfileAnalysisSettingsResponse(List<String> keywords, int minimumMatches) {

    static MonitoringProfileAnalysisSettingsResponse from(MonitoringProfileAnalysisSettings settings) {
        return new MonitoringProfileAnalysisSettingsResponse(settings.keywords(), settings.minimumMatches());
    }
}
