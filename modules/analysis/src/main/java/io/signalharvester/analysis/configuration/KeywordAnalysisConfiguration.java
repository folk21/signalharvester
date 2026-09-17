package io.signalharvester.analysis.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** Supplies compatibility keyword defaults only for legacy raw events that predate settings snapshots. */
@Context
@ConfigurationProperties("signalharvester.analysis.keyword-rules")
public interface KeywordAnalysisConfiguration {

    /** Returns compatibility keywords for legacy raw events without an Analysis settings snapshot. */
    @NotEmpty
    List<@NotBlank String> getKeywords();

    /** Returns the compatibility threshold for legacy raw events without an Analysis settings snapshot. */
    @Min(1)
    @Bindable(defaultValue = "1")
    int getMinimumMatches();
}
