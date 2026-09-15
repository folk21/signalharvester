package io.signalharvester.analysis.observability;

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

/** Records Analysis telemetry and restores trace context across the transactional-outbox boundary. */
@Singleton
public final class AnalysisObservability {

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

    public AnalysisObservability(Optional<MeterRegistry> meterRegistry, Optional<OpenTelemetry> openTelemetry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        OpenTelemetry telemetry = Objects.requireNonNull(openTelemetry, "openTelemetry")
                .orElseGet(OpenTelemetry::noop);
        this.propagator = telemetry.getPropagators().getTextMapPropagator();
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
        meterRegistry.ifPresent(registry -> {
            registry.counter("signalharvester.analysis.items", "status", status).increment();
            registry.timer("signalharvester.analysis.processing.duration", "status", status).record(duration);
        });
    }

    /** Records one outbox publication attempt. */
    public void recordOutboxPublication(String outcome) {
        meterRegistry.ifPresent(registry ->
                registry.counter("signalharvester.analysis.outbox.publications", "outcome", outcome).increment());
    }
}
