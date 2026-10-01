package io.signalharvester.operations.alert;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;

/** Runtime configuration for deterministic human-attention alert decisions. */
@Context
@ConfigurationProperties("signalharvester.operations.alert-policy")
public interface AlertPolicyConfiguration {
    /** Alert recording is opt-in until an operator explicitly enables the policy. */
    @Bindable(defaultValue = "false")
    boolean isEnabled();

    /** Stable policy identifier persisted with every alert decision. */
    @Bindable(defaultValue = "human-attention-v1")
    String getPolicyVersion();

    /** Consecutive DEGRADED snapshots required before opening a WARNING alert. */
    @Min(1)
    @Max(100)
    @Bindable(defaultValue = "3")
    int getDegradedMinConsecutiveSnapshots();

    /** Consecutive UNHEALTHY snapshots required before opening a CRITICAL alert. */
    @Min(1)
    @Max(100)
    @Bindable(defaultValue = "1")
    int getUnhealthyMinConsecutiveSnapshots();

    /** Consecutive HEALTHY snapshots required before resolving the active alert. */
    @Min(1)
    @Max(100)
    @Bindable(defaultValue = "2")
    int getHealthyMinConsecutiveSnapshotsToResolve();

    /** Minimum time before a resolved incident may reopen as DEGRADED; UNHEALTHY bypasses this cooldown. */
    @NotNull
    @Bindable(defaultValue = "15m")
    Duration getReopenCooldown();

    /** Maximum number of resolved alerts retained in addition to any currently open alert. */
    @Min(1)
    @Max(100_000)
    @Bindable(defaultValue = "500")
    int getRetentionCount();
}
