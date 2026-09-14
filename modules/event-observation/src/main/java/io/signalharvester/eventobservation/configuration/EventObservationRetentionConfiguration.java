package io.signalharvester.eventobservation.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;

/** Bounded retention policy for diagnostic event-observation history. */
@Context
@ConfigurationProperties("signalharvester.event-observation.retention")
public interface EventObservationRetentionConfiguration {

    @Positive
    @Max(1_000_000)
    @Bindable(defaultValue = "10000")
    int getMaxEvents();

    @NotNull
    @Bindable(defaultValue = "24h")
    Duration getMaxAge();
}
