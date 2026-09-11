package io.signalharvester.analysis.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.NotBlank;

/**
 * Provides analysis-owned Kafka output topic settings.
 */
@Context
@ConfigurationProperties("signalharvester.kafka")
public interface AnalysisKafkaConfiguration {

    /** Returns the version-one topic for analyzed items. */
    @NotBlank
    @Bindable(defaultValue = "signalharvester.analysis.item-analyzed.v1")
    String getItemAnalyzedTopic();

    /** Returns the version-one topic for intentionally rejected items. */
    @NotBlank
    @Bindable(defaultValue = "signalharvester.analysis.item-rejected.v1")
    String getItemRejectedTopic();
}
