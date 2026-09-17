package io.signalharvester.configuration.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Supplies compatibility defaults for profiles that predate persisted Analysis settings or omit them on create.
 */
@ConfigurationProperties("signalharvester.analysis.keyword-rules")
public final class MonitoringProfileAnalysisDefaultsConfiguration {

    private static final List<String> DEFAULT_KEYWORDS = List.of(
            "java",
            "kafka",
            "postgresql",
            "backend",
            "artificial intelligence",
            "machine learning");

    @NotEmpty
    private List<@NotBlank String> keywords = DEFAULT_KEYWORDS;

    @Min(1)
    private int minimumMatches = 1;

    /** Returns the compatibility keyword list previously consumed directly by Analysis. */
    public List<String> getKeywords() {
        return keywords;
    }

    /** Replaces the compatibility keyword list from runtime configuration. */
    public void setKeywords(List<String> keywords) {
        this.keywords = List.copyOf(keywords);
    }

    /** Returns the compatibility minimum-match threshold. */
    public int getMinimumMatches() {
        return minimumMatches;
    }

    /** Replaces the compatibility minimum-match threshold from runtime configuration. */
    public void setMinimumMatches(int minimumMatches) {
        this.minimumMatches = minimumMatches;
    }
}
