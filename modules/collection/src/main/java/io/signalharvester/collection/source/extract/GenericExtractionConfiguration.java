package io.signalharvester.collection.source.extract;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;

/** Runtime bounds for configuration-driven REST/JSON and HTML extraction. */
@Context
@ConfigurationProperties("signalharvester.collection.extraction")
public interface GenericExtractionConfiguration {

    /**
     * Returns the maximum number of semantic items accepted from one generic source response.
     *
     * @return positive per-source item bound
     */
    @Positive
    @Max(10_000)
    @Bindable(defaultValue = "500")
    int getMaxItemsPerSource();
}
