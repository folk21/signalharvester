package io.signalharvester.results.http;

import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.TaskScheduler;
import io.signalharvester.common.concurrent.DemandDrivenPollingLoop;
import io.signalharvester.results.application.ResultLiveBatch;
import io.signalharvester.results.application.ResultLiveCriteria;
import io.signalharvester.results.application.ResultLiveQuery;
import io.signalharvester.results.application.ResultLiveUpdate;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ExecutorService;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Creates backpressure-aware SSE publishers that poll durable Results cursors off the Netty event loop.
 * Generic demand, scheduling, and cancellation lifecycle is delegated to the shared polling utility.
 */
@Singleton
public final class ResultSseStream {

    private final ResultLiveQuery query;
    private final ExecutorService blockingExecutor;
    private final TaskScheduler taskScheduler;
    private final ResultSseConfiguration configuration;

    public ResultSseStream(
            ResultLiveQuery query,
            @Named(TaskExecutors.BLOCKING) ExecutorService blockingExecutor,
            @Named(TaskExecutors.SCHEDULED) TaskScheduler taskScheduler,
            ResultSseConfiguration configuration) {
        this.query = Objects.requireNonNull(query, "query");
        this.blockingExecutor = Objects.requireNonNull(blockingExecutor, "blockingExecutor");
        this.taskScheduler = Objects.requireNonNull(taskScheduler, "taskScheduler");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        requirePositive(configuration.getPollInterval(), "pollInterval");
        requirePositive(configuration.getKeepaliveInterval(), "keepaliveInterval");
        requirePositive(configuration.getReconnectDelay(), "reconnectDelay");
    }

    /** Creates one resumable live stream for the supplied filters and optional SSE resume cursor. */
    public Publisher<Event<ResultLiveEventResponse>> stream(
            ResultLiveCriteria criteria, OptionalLong lastEventId) {
        Objects.requireNonNull(criteria, "criteria");
        Objects.requireNonNull(lastEventId, "lastEventId");
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            LiveSubscription subscription = new LiveSubscription(subscriber, criteria, lastEventId);
            subscriber.onSubscribe(subscription);
        };
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private final class LiveSubscription implements Subscription {
        private final Subscriber<? super Event<ResultLiveEventResponse>> subscriber;
        private final ResultLiveCriteria criteria;
        private final Deque<Event<ResultLiveEventResponse>> pending = new ArrayDeque<>();
        private final DemandDrivenPollingLoop<Event<ResultLiveEventResponse>> pollingLoop;

        private long cursor;
        private boolean initialized;
        private long lastEmissionNanos = System.nanoTime();

        private LiveSubscription(
                Subscriber<? super Event<ResultLiveEventResponse>> subscriber,
                ResultLiveCriteria criteria,
                OptionalLong lastEventId) {
            this.subscriber = subscriber;
            this.criteria = criteria;
            this.cursor = lastEventId.orElse(0L);
            this.initialized = lastEventId.isPresent();
            this.pollingLoop = new DemandDrivenPollingLoop<>(
                    blockingExecutor,
                    (delay, task) -> taskScheduler.schedule(delay, task),
                    configuration.getPollInterval(),
                    this::nextEvent,
                    this::emit,
                    subscriber::onError);
        }

        @Override
        public void request(long n) {
            pollingLoop.request(n);
        }

        @Override
        public void cancel() {
            pollingLoop.cancel();
        }

        private Optional<Event<ResultLiveEventResponse>> nextEvent() {
            if (!pending.isEmpty()) {
                return Optional.of(pending.removeFirst());
            }
            if (!initialized) {
                cursor = query.currentCursor();
                initialized = true;
                return Optional.of(readyEvent(cursor));
            }

            ResultLiveBatch batch = query.pollAfter(cursor, criteria, configuration.getBatchSize());
            cursor = batch.nextCursor();
            for (ResultLiveUpdate update : batch.updates()) {
                pending.add(resultEvent(update));
            }
            if (!pending.isEmpty()) {
                return Optional.of(pending.removeFirst());
            }
            if (keepaliveDue()) {
                return Optional.of(keepaliveEvent(cursor));
            }
            return Optional.empty();
        }

        private Event<ResultLiveEventResponse> readyEvent(long eventCursor) {
            return Event.of(new ResultLiveEventResponse(eventCursor, null))
                    .id(Long.toString(eventCursor))
                    .name("ready")
                    .retry(configuration.getReconnectDelay());
        }

        private Event<ResultLiveEventResponse> resultEvent(ResultLiveUpdate update) {
            return Event.of(new ResultLiveEventResponse(
                            update.eventId(), ResultSummaryResponse.from(update.result())))
                    .id(Long.toString(update.eventId()))
                    .name("result")
                    .retry(configuration.getReconnectDelay());
        }

        private Event<ResultLiveEventResponse> keepaliveEvent(long eventCursor) {
            return Event.of(new ResultLiveEventResponse(eventCursor, null))
                    .id(Long.toString(eventCursor))
                    .name("keepalive");
        }

        private boolean keepaliveDue() {
            long elapsed = System.nanoTime() - lastEmissionNanos;
            return elapsed >= configuration.getKeepaliveInterval().toNanos();
        }

        private void emit(Event<ResultLiveEventResponse> event) {
            if (pollingLoop.isCancelled()) {
                return;
            }
            subscriber.onNext(event);
            lastEmissionNanos = System.nanoTime();
        }
    }
}
