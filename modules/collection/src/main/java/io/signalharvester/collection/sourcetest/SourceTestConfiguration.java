package io.signalharvester.collection.sourcetest;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;

/** Runtime bounds for diagnostic source-test previews. */
@Context
@ConfigurationProperties("signalharvester.collection.source-test")
public interface SourceTestConfiguration {

    /** Returns the maximum number of extracted items included in one diagnostic preview. */
    @Positive
    @Max(50)
    @Bindable(defaultValue = "5")
    int getMaxPreviewItems();

    /** Returns the maximum number of characters included from one extracted item. */
    @Positive
    @Max(5_000)
    @Bindable(defaultValue = "500")
    int getMaxPreviewContentChars();
}
