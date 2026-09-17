package io.signalharvester.configuration.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micronaut.context.ApplicationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies standalone binding and fallback values for {@link MonitoringProfileAnalysisDefaultsConfiguration}. */
class MonitoringProfileAnalysisDefaultsConfigurationTest {

    /** Provide historical compatibility defaults when the composition root does not supply overrides. */
    @Test
    void shouldProvideStandaloneCompatibilityDefaults() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
                "datasources.default.enabled", false,
                "flyway.datasources.default.enabled", false))) {
            MonitoringProfileAnalysisDefaultsConfiguration configuration =
                    context.getBean(MonitoringProfileAnalysisDefaultsConfiguration.class);

            assertEquals(
                    List.of("java", "kafka", "postgresql", "backend", "artificial intelligence", "machine learning"),
                    configuration.getKeywords());
            assertEquals(1, configuration.getMinimumMatches());
        }
    }

    /** Bind deployment overrides without changing the standalone fallback contract. */
    @Test
    void shouldBindCompatibilityOverrides() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
                "datasources.default.enabled", false,
                "flyway.datasources.default.enabled", false,
                "signalharvester.analysis.keyword-rules.keywords", List.of("custom", "fallback"),
                "signalharvester.analysis.keyword-rules.minimum-matches", 2))) {
            MonitoringProfileAnalysisDefaultsConfiguration configuration =
                    context.getBean(MonitoringProfileAnalysisDefaultsConfiguration.class);

            assertEquals(List.of("custom", "fallback"), configuration.getKeywords());
            assertEquals(2, configuration.getMinimumMatches());
        }
    }
}
