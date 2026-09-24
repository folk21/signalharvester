package io.signalharvester.analysis.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.analysis.configuration.AnalysisClockFactory;
import io.signalharvester.analysis.configuration.AnalysisOutboxConfiguration;
import io.signalharvester.analysis.observability.AnalysisObservability;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Samples global Analysis outbox backlog state without changing dispatcher semantics. */
@Singleton
@Requires(property = "signalharvester.analysis.enabled", notEquals = "false", defaultValue = "true")
@Requires(property = "signalharvester.analysis.outbox.enabled", notEquals = "false", defaultValue = "true")
@Requires(beans = MeterRegistry.class)
public final class AnalysisOutboxMetricsSampler {

    private static final Logger LOG = LoggerFactory.getLogger(AnalysisOutboxMetricsSampler.class);

    private final AnalysisOutboxStore store;
    private final TransactionOperations<Connection> transactions;
    private final AnalysisObservability observability;
    private final Clock clock;

    public AnalysisOutboxMetricsSampler(
            AnalysisOutboxStore store,
            @Named("default") TransactionOperations<Connection> transactions,
            AnalysisObservability observability,
            AnalysisOutboxConfiguration configuration,
            @Named(AnalysisClockFactory.ANALYSIS_CLOCK) Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.observability = Objects.requireNonNull(observability, "observability");
        AnalysisOutboxConfiguration outboxConfiguration = Objects.requireNonNull(configuration, "configuration");
        this.clock = Objects.requireNonNull(clock, "clock");
        Duration metricsInterval = Objects.requireNonNull(outboxConfiguration.getMetricsInterval(), "metricsInterval");
        if (metricsInterval.compareTo(Duration.ofSeconds(1)) < 0 || metricsInterval.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("metricsInterval must be between PT1S and PT1M");
        }
    }

    /** Samples pending-row count and oldest pending age; failures remain observability-only. */
    @Scheduled(fixedDelay = "${signalharvester.analysis.outbox.metrics-interval:5s}")
    public void sample() {
        long started = System.nanoTime();
        try {
            AnalysisOutboxBacklog backlog = transactions.executeRead(status -> store.inspectBacklog());
            observability.recordOutboxDatabaseOperation("inspect_backlog", "success", elapsed(started));
            Instant now = clock.instant();
            Duration oldestAge = backlog.oldestCreatedAt()
                    .map(createdAt -> nonNegative(Duration.between(createdAt, now)))
                    .orElse(Duration.ZERO);
            observability.updateOutboxBacklog(backlog.pendingCount(), oldestAge);
        } catch (RuntimeException failure) {
            observability.recordOutboxDatabaseOperation("inspect_backlog", "failed", elapsed(started));
            LOG.warn("Failed to sample Analysis outbox backlog metrics", failure);
        }
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - started));
    }

    private static Duration nonNegative(Duration value) {
        return value.isNegative() ? Duration.ZERO : value;
    }
}
