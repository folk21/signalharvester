package io.signalharvester.analysis.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micronaut.context.ApplicationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies standalone binding and fallback values for {@link KeywordAnalysisConfiguration}. */
class KeywordAnalysisConfigurationTest {

    /** Provide historical legacy-event defaults when the composition root does not supply overrides. */
    @Test
    void shouldProvideStandaloneCompatibilityDefaults() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
                "datasources.default.enabled", false,
                "flyway.datasources.default.enabled", false,
                "kafka.enabled", false,
                "signalharvester.analysis.enabled", false))) {
            KeywordAnalysisConfiguration configuration = context.getBean(KeywordAnalysisConfiguration.class);

            assertEquals(
                    List.of("java", "kafka", "postgresql", "backend", "artificial intelligence", "machine learning"),
                    configuration.getKeywords());
            assertEquals(1, configuration.getMinimumMatches());
        }
    }

    /** Bind deployment overrides without changing the standalone compatibility fallback. */
    @Test
    void shouldBindCompatibilityOverrides() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
                "datasources.default.enabled", false,
                "flyway.datasources.default.enabled", false,
                "kafka.enabled", false,
                "signalharvester.analysis.enabled", false,
                "signalharvester.analysis.keyword-rules.keywords", List.of("custom", "fallback"),
                "signalharvester.analysis.keyword-rules.minimum-matches", 2))) {
            KeywordAnalysisConfiguration configuration = context.getBean(KeywordAnalysisConfiguration.class);

            assertEquals(List.of("custom", "fallback"), configuration.getKeywords());
            assertEquals(2, configuration.getMinimumMatches());
        }
    }
}
