package io.signalharvester.analysis.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;

/** Provides bounded polling, leasing, and retry settings for Analysis outbox delivery. */
@Context
@ConfigurationProperties("signalharvester.analysis.outbox")
public interface AnalysisOutboxConfiguration {

    /** Returns whether the background outbox dispatcher is enabled. */
    @Bindable(defaultValue = "true")
    boolean isEnabled();

    /** Returns the scheduled delay between bounded dispatch passes. */
    @NotNull
    @Bindable(defaultValue = "1s")
    Duration getPollInterval();

    /** Returns the maximum number of events claimed per dispatch pass. */
    @Positive
    @Max(500)
    @Bindable(defaultValue = "100")
    int getBatchSize();

    /** Returns how long one replica owns a claimed event before another replica may reclaim it. */
    @NotNull
    @Bindable(defaultValue = "30s")
    Duration getLeaseDuration();

    /** Returns how long a failed publish waits before it becomes claimable again. */
    @NotNull
    @Bindable(defaultValue = "2s")
    Duration getRetryBackoff();
}
