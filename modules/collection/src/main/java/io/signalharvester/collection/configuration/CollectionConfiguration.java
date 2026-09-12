package io.signalharvester.collection.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.Positive;

/**
 * Provides validated runtime settings that control collection orchestration.
 */
@Context
@ConfigurationProperties("signalharvester.collection")
public interface CollectionConfiguration {

    /**
     * Returns the maximum number of external source requests that may be active concurrently.
     *
     * @return positive concurrency limit
     */
    @Positive
    @Bindable(defaultValue = "8")
    int getMaxConcurrency();
}
