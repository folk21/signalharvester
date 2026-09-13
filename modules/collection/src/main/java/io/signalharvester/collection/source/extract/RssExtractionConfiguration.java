package io.signalharvester.collection.source.extract;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;

/** Runtime limits for RSS/Atom extraction. */
@Context
@ConfigurationProperties("signalharvester.collection.rss")
public interface RssExtractionConfiguration {

    /** Maximum number of feed entries accepted from one fetched response. */
    @Positive
    @Max(10_000)
    @Bindable(defaultValue = "500")
    int getMaxItemsPerSource();
}
