package io.signalharvester.analysis.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** Supplies compatibility keyword defaults only for legacy raw events that predate settings snapshots. */
@Context
@ConfigurationProperties("signalharvester.analysis.keyword-rules")
public final class KeywordAnalysisConfiguration {

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

    /** Returns compatibility keywords for legacy raw events without an Analysis settings snapshot. */
    public List<String> getKeywords() {
        return keywords;
    }

    /** Replaces compatibility keywords from runtime configuration. */
    public void setKeywords(List<String> keywords) {
        this.keywords = List.copyOf(keywords);
    }

    /** Returns the compatibility threshold for legacy raw events without an Analysis settings snapshot. */
    public int getMinimumMatches() {
        return minimumMatches;
    }

    /** Replaces the compatibility threshold from runtime configuration. */
    public void setMinimumMatches(int minimumMatches) {
        this.minimumMatches = minimumMatches;
    }
}
