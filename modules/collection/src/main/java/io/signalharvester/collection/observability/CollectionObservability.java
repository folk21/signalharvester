package io.signalharvester.collection.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Records low-cardinality Collection telemetry and owns the collection-run application span. */
@Singleton
public final class CollectionObservability {

    private static final String INSTRUMENTATION_NAME = "io.signalharvester.collection";

    private final Optional<MeterRegistry> meterRegistry;
    private final Tracer tracer;

    public CollectionObservability(Optional<MeterRegistry> meterRegistry, Optional<OpenTelemetry> openTelemetry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        OpenTelemetry telemetry = Objects.requireNonNull(openTelemetry, "openTelemetry")
                .orElseGet(OpenTelemetry::noop);
        this.tracer = telemetry.getTracer(INSTRUMENTATION_NAME);
    }

    /** Starts one application span around a manual or scheduled collection run. */
    public RunSpan startRun(String monitoringProfileId) {
        Span span = tracer.spanBuilder("collection.run")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("signalharvester.monitoring_profile_id", monitoringProfileId)
                .startSpan();
        return new RunSpan(span, span.makeCurrent());
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

    /** Records a terminal collection-run outcome and elapsed time. */
    public void recordRun(String status, Duration duration) {
        meterRegistry.ifPresent(registry -> {
            registry.counter("signalharvester.collection.runs", "status", status).increment();
            registry.timer("signalharvester.collection.run.duration", "status", status).record(duration);
        });
    }

    /** Records one external source fetch without using source identity as a metric label. */
    public void recordSourceFetch(String outcome, Duration duration) {
        meterRegistry.ifPresent(registry -> {
            registry.counter("signalharvester.collection.source.fetches", "outcome", outcome).increment();
            registry.timer("signalharvester.collection.source.fetch.duration", "outcome", outcome).record(duration);
        });
    }

    /** Scope for one collection-run span. */
    public static final class RunSpan implements AutoCloseable {
        private final Span span;
        private final Scope scope;
        private boolean closed;

        private RunSpan(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }

        /** Marks the span as failed while retaining the original exception. */
        public void recordFailure(Throwable failure) {
            span.recordException(Objects.requireNonNull(failure, "failure"));
            span.setStatus(StatusCode.ERROR);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            scope.close();
            span.end();
        }
    }
}
