package io.signalharvester.collection.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;

/** Runtime settings for persisted monitoring-profile scheduling. */
@Context
@ConfigurationProperties("signalharvester.collection.scheduler")
public interface CollectionSchedulerConfiguration {

    /** Returns whether automatic monitoring-profile scheduling is enabled. */
    @Bindable(defaultValue = "true")
    boolean isEnabled();

    /** Returns the lease duration used to prevent concurrent execution across replicas. */
    @NotNull
    @Bindable(defaultValue = "2m")
    Duration getLeaseDuration();

    /** Returns how often an active run renews its scheduling lease. */
    @NotNull
    @Bindable(defaultValue = "30s")
    Duration getHeartbeatInterval();
}
