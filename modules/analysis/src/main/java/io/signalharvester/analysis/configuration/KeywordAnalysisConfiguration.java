package io.signalharvester.analysis.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Configures the initial deterministic keyword analyzer until profile-owned analysis rules exist.
 */
@Context
@ConfigurationProperties("signalharvester.analysis.keyword-rules")
public interface KeywordAnalysisConfiguration {

    /** Returns normalized candidate keywords used by the deterministic analyzer. */
    @NotEmpty
    List<@NotBlank String> getKeywords();

    /** Returns how many configured keywords must match before an item is considered relevant. */
    @Min(1)
    @Bindable(defaultValue = "1")
    int getMinimumMatches();
}
