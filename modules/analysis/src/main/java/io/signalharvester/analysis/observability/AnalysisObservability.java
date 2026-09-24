package io.signalharvester.analysis.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Records Analysis telemetry and restores trace context across the transactional-outbox boundary. */
@Singleton
public final class AnalysisObservability {

    private static final Logger LOG = LoggerFactory.getLogger(AnalysisObservability.class);

    private static final TextMapGetter<String> TRACEPARENT_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(String carrier) {
            return carrier == null ? Collections.emptyList() : java.util.List.of("traceparent");
        }

        @Override
        public String get(String carrier, String key) {
            return carrier != null && "traceparent".equalsIgnoreCase(key) ? carrier : null;
        }
    };

    private final Optional<MeterRegistry> meterRegistry;
    private final TextMapPropagator propagator;
    private final AtomicLong outboxPendingRows = new AtomicLong();
    private final AtomicLong outboxOldestPendingAgeMillis = new AtomicLong();

    public AnalysisObservability(Optional<MeterRegistry> meterRegistry, Optional<OpenTelemetry> openTelemetry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        OpenTelemetry telemetry = Objects.requireNonNull(openTelemetry, "openTelemetry")
                .orElseGet(OpenTelemetry::noop);
        this.propagator = telemetry.getPropagators().getTextMapPropagator();
        meterRegistry.ifPresent(registry -> {
            Gauge.builder("signalharvester.analysis.outbox.pending", outboxPendingRows, value -> value.get())
                    .description("Current unpublished Analysis outbox row count sampled from PostgreSQL")
                    .register(registry);
            Gauge.builder(
                            "signalharvester.analysis.outbox.oldest.pending.age",
                            outboxOldestPendingAgeMillis,
                            value -> value.doubleValue() / 1000.0)
                    .description("Age in seconds of the oldest unpublished Analysis outbox row")
                    .baseUnit("seconds")
                    .register(registry);
        });
    }

    /** Returns the active W3C traceparent value when a valid tracing context is current. */
    public Optional<String> currentTraceparent() {
        var context = Span.current().getSpanContext();
        if (!context.isValid()) {
            return Optional.empty();
        }
        String flags = context.isSampled() ? "01" : "00";
        return Optional.of("00-" + context.getTraceId() + "-" + context.getSpanId() + "-" + flags);
    }

    /** Runs one action with a persisted traceparent restored as the current parent context. */
    public void withTraceparent(Optional<String> traceparent, Runnable action) {
        Objects.requireNonNull(traceparent, "traceparent");
        Objects.requireNonNull(action, "action");
        if (traceparent.isEmpty()) {
            action.run();
            return;
        }
        Context context = propagator.extract(Context.root(), traceparent.get(), TRACEPARENT_GETTER);
        try (Scope ignored = context.makeCurrent()) {
            action.run();
        }
    }

    /** Records one terminal raw-item processing outcome. */
    public void recordProcessing(String status, Duration duration) {
        recordSafely(registry -> {
            registry.counter("signalharvester.analysis.items", "status", status).increment();
            registry.timer("signalharvester.analysis.processing.duration", "status", status).record(duration);
        });
    }

    /** Records one outbox publication attempt. */
    public void recordOutboxPublication(String outcome) {
        recordSafely(registry ->
                registry.counter("signalharvester.analysis.outbox.publications", "outcome", outcome).increment());
    }

    /** Updates sampled global Analysis outbox backlog gauges. */
    public void updateOutboxBacklog(long pendingRows, Duration oldestPendingAge) {
        if (pendingRows < 0) {
            throw new IllegalArgumentException("pendingRows must not be negative");
        }
        Objects.requireNonNull(oldestPendingAge, "oldestPendingAge");
        if (oldestPendingAge.isNegative()) {
            throw new IllegalArgumentException("oldestPendingAge must not be negative");
        }
        outboxPendingRows.set(pendingRows);
        outboxOldestPendingAgeMillis.set(oldestPendingAge.toMillis());
    }

    /** Records one non-empty claimed outbox batch and its local processing duration. */
    public void recordOutboxBatch(int batchSize, Duration duration) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        Objects.requireNonNull(duration, "duration");
        recordSafely(registry -> {
            registry.summary("signalharvester.analysis.outbox.batch.size").record(batchSize);
            registry.timer("signalharvester.analysis.outbox.batch.duration").record(duration);
        });
    }

    /** Records Kafka send latency for one outbox publication attempt. */
    public void recordOutboxKafkaPublish(String outcome, Duration duration) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(duration, "duration");
        recordSafely(registry -> registry
                .timer("signalharvester.analysis.outbox.kafka.publish.duration", "outcome", outcome)
                .record(duration));
    }

    /** Records one transactional outbox database operation latency. */
    public void recordOutboxDatabaseOperation(String operation, String outcome, Duration duration) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(duration, "duration");
        recordSafely(registry -> registry
                .timer(
                        "signalharvester.analysis.outbox.database.operation.duration",
                        "operation",
                        operation,
                        "outcome",
                        outcome)
                .record(duration));
    }

    private void recordSafely(Consumer<MeterRegistry> action) {
        meterRegistry.ifPresent(registry -> {
            try {
                action.accept(registry);
            } catch (RuntimeException failure) {
                LOG.warn("Failed to record Analysis metric", failure);
            }
        });
    }
}
