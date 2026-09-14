package io.signalharvester.eventobservation.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;

/** Runtime bounds and timing for the Event Explorer SSE stream. */
@Context
@ConfigurationProperties("signalharvester.event-observation.sse")
public interface EventObservationSseConfiguration {

    @NotNull
    @Bindable(defaultValue = "1s")
    Duration getPollInterval();

    @NotNull
    @Bindable(defaultValue = "15s")
    Duration getKeepaliveInterval();

    @NotNull
    @Bindable(defaultValue = "2s")
    Duration getReconnectDelay();

    @Positive
    @Max(500)
    @Bindable(defaultValue = "200")
    int getBatchSize();
}
