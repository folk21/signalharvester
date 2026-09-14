package io.signalharvester.results.http;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.bind.annotation.Bindable;
import io.signalharvester.results.application.ResultLiveQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;

/** Runtime bounds and timing for the Results server-sent-event stream. */
@Context
@ConfigurationProperties("signalharvester.results.sse")
public interface ResultSseConfiguration {

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
    @Max(ResultLiveQueryService.MAX_BATCH_SIZE)
    @Bindable(defaultValue = "100")
    int getBatchSize();
}
