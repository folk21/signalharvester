package io.signalharvester.collection.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.NotBlank;

/**
 * Provides collection-owned Kafka publication settings.
 */
@Context
@ConfigurationProperties("signalharvester.kafka")
public interface CollectionKafkaConfiguration {

    /**
     * Returns the topic used for version-one raw-item discovery events.
     *
     * @return non-blank Kafka topic name
     */
    @NotBlank
    @Bindable(defaultValue = "signalharvester.collection.raw-item-discovered.v1")
    String getRawItemDiscoveredTopic();
}
