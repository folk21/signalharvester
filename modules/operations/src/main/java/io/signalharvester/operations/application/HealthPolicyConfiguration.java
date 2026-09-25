package io.signalharvester.operations.application;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;

/** Configurable versioned policy for operational health sampling and deterministic/statistical scoring. */
@Context
@ConfigurationProperties("signalharvester.operations.health")
public interface HealthPolicyConfiguration {

    /** Returns the persisted policy identifier used to interpret generated snapshots. */
    @NotBlank
    @Bindable(defaultValue = "deterministic-statistical-v1")
    String getPolicyVersion();

    /** Returns whether bounded periodic Health Snapshot sampling is enabled. */
    @Bindable(defaultValue = "true")
    boolean isSamplingEnabled();

    /** Returns the delay between periodic Health Snapshot attempts. */
    @NotNull
    @Bindable(defaultValue = "1m")
    Duration getSamplingInterval();

    /** Returns the initial delay before the first periodic Health Snapshot attempt. */
    @NotNull
    @Bindable(defaultValue = "30s")
    Duration getSamplingInitialDelay();

    /** Returns the telemetry analysis window used by Prometheus rate queries. */
    @NotNull
    @Bindable(defaultValue = "5m")
    Duration getWindow();

    /** Returns the optional Prometheus HTTP base URL; blank disables cluster-wide Prometheus evidence. */
    @Bindable(defaultValue = "")
    String getPrometheusBaseUrl();

    /** Returns the timeout applied independently to each bounded Prometheus query. */
    @NotNull
    @Bindable(defaultValue = "2s")
    Duration getPrometheusQueryTimeout();

    /** Returns the minimum number of prior snapshots required for rolling statistical comparison. */
    @Min(3)
    @Max(100)
    @Bindable(defaultValue = "5")
    int getBaselineMinSamples();

    /** Returns the maximum number of prior snapshots used by the rolling baseline. */
    @Min(5)
    @Max(200)
    @Bindable(defaultValue = "20")
    int getBaselineMaxSamples();

    /** Returns the robust-z threshold that produces a DEGRADED statistical anomaly. */
    @DecimalMin("1.0")
    @Bindable(defaultValue = "3.5")
    double getDegradedRobustZScore();

    /** Returns the robust-z threshold that produces an UNHEALTHY statistical anomaly. */
    @DecimalMin("1.0")
    @Bindable(defaultValue = "6.0")
    double getUnhealthyRobustZScore();

    /** Returns the unavailable backend-replica threshold for DEGRADED state. */
    @Positive
    @Bindable(defaultValue = "1")
    int getBackendUnavailableReplicasDegraded();

    /** Returns the unavailable backend-replica threshold for UNHEALTHY state. */
    @Positive
    @Bindable(defaultValue = "2")
    int getBackendUnavailableReplicasUnhealthy();

    /** Returns the pending Analysis-outbox row threshold for DEGRADED state. */
    @Positive
    @Bindable(defaultValue = "250")
    long getOutboxPendingDegraded();

    /** Returns the pending Analysis-outbox row threshold for UNHEALTHY state. */
    @Positive
    @Bindable(defaultValue = "1000")
    long getOutboxPendingUnhealthy();

    /** Returns the oldest pending Analysis-outbox age threshold for DEGRADED state. */
    @NotNull
    @Bindable(defaultValue = "30s")
    Duration getOutboxOldestPendingDegraded();

    /** Returns the oldest pending Analysis-outbox age threshold for UNHEALTHY state. */
    @NotNull
    @Bindable(defaultValue = "2m")
    Duration getOutboxOldestPendingUnhealthy();

    /** Returns the Kafka consumer-lag threshold for DEGRADED state. */
    @Positive
    @Bindable(defaultValue = "500")
    long getKafkaLagDegraded();

    /** Returns the Kafka consumer-lag threshold for UNHEALTHY state. */
    @Positive
    @Bindable(defaultValue = "5000")
    long getKafkaLagUnhealthy();

    /** Returns the HTTP 5xx ratio threshold for DEGRADED state. */
    @DecimalMin("0.0")
    @Bindable(defaultValue = "0.05")
    double getHttpErrorRatioDegraded();

    /** Returns the HTTP 5xx ratio threshold for UNHEALTHY state. */
    @DecimalMin("0.0")
    @Bindable(defaultValue = "0.20")
    double getHttpErrorRatioUnhealthy();

    /** Returns the backend HTTP average-latency threshold for DEGRADED state. */
    @NotNull
    @Bindable(defaultValue = "1s")
    Duration getHttpLatencyDegraded();

    /** Returns the backend HTTP average-latency threshold for UNHEALTHY state. */
    @NotNull
    @Bindable(defaultValue = "3s")
    Duration getHttpLatencyUnhealthy();

    /** Returns the PostgreSQL average span-latency threshold for DEGRADED state. */
    @NotNull
    @Bindable(defaultValue = "250ms")
    Duration getPostgresLatencyDegraded();

    /** Returns the PostgreSQL average span-latency threshold for UNHEALTHY state. */
    @NotNull
    @Bindable(defaultValue = "1s")
    Duration getPostgresLatencyUnhealthy();

    /** Returns the failure-ratio threshold used for Collection, Analysis, and outbox publication. */
    @DecimalMin("0.0")
    @Bindable(defaultValue = "0.10")
    double getFailureRatioDegraded();

    /** Returns the failure-ratio threshold used for UNHEALTHY Collection, Analysis, and outbox publication. */
    @DecimalMin("0.0")
    @Bindable(defaultValue = "0.50")
    double getFailureRatioUnhealthy();

    /** Returns the health-score penalty for each DEGRADED signal after de-duplication by signal. */
    @Min(1)
    @Max(100)
    @Bindable(defaultValue = "15")
    int getDegradedPenalty();

    /** Returns the health-score penalty for each UNHEALTHY signal after de-duplication by signal. */
    @Min(1)
    @Max(100)
    @Bindable(defaultValue = "35")
    int getUnhealthyPenalty();
}
