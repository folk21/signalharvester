package io.signalharvester.eventobservation.http;

import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.TaskScheduler;
import io.signalharvester.eventobservation.application.EventObservationCriteria;
import io.signalharvester.eventobservation.application.EventObservationLiveBatch;
import io.signalharvester.eventobservation.application.EventObservationQuery;
import io.signalharvester.eventobservation.configuration.EventObservationSseConfiguration;
import io.signalharvester.eventobservation.model.ObservedEvent;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/** Creates backpressure-aware Event Explorer SSE publishers over durable observation history. */
@Singleton
public final class EventObservationSseStream {

    private final EventObservationQuery query;
    private final ExecutorService blockingExecutor;
    private final TaskScheduler taskScheduler;
    private final EventObservationSseConfiguration configuration;

    public EventObservationSseStream(
            EventObservationQuery query,
            @Named(TaskExecutors.BLOCKING) ExecutorService blockingExecutor,
            @Named(TaskExecutors.SCHEDULED) TaskScheduler taskScheduler,
            EventObservationSseConfiguration configuration) {
        this.query = Objects.requireNonNull(query, "query");
        this.blockingExecutor = Objects.requireNonNull(blockingExecutor, "blockingExecutor");
        this.taskScheduler = Objects.requireNonNull(taskScheduler, "taskScheduler");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        requirePositive(configuration.getPollInterval(), "pollInterval");
        requirePositive(configuration.getKeepaliveInterval(), "keepaliveInterval");
        requirePositive(configuration.getReconnectDelay(), "reconnectDelay");
    }

    /** Creates one resumable stream for the supplied filters and optional SSE cursor. */
    public Publisher<Event<EventObservationLiveEventResponse>> stream(
            EventObservationCriteria criteria, OptionalLong lastEventId) {
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
        private final Subscriber<? super Event<EventObservationLiveEventResponse>> subscriber;
        private final EventObservationCriteria criteria;
        private final AtomicLong demand = new AtomicLong();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();
        private final Deque<Event<EventObservationLiveEventResponse>> pending = new ArrayDeque<>();

        private long cursor;
        private boolean initialized;
        private long lastEmissionNanos = System.nanoTime();

        private LiveSubscription(
                Subscriber<? super Event<EventObservationLiveEventResponse>> subscriber,
                EventObservationCriteria criteria,
                OptionalLong lastEventId) {
            this.subscriber = subscriber;
            this.criteria = criteria;
            this.cursor = lastEventId.orElse(0L);
            this.initialized = lastEventId.isPresent();
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                fail(new IllegalArgumentException("Reactive Streams demand must be positive"));
                return;
            }
            demand.getAndUpdate(current -> addCap(current, n));
            trigger();
        }

        @Override
        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            ScheduledFuture<?> future = scheduled.getAndSet(null);
            if (future != null) {
                future.cancel(false);
            }
        }

        private void trigger() {
            if (cancelled.get() || demand.get() == 0 || !running.compareAndSet(false, true)) {
                return;
            }
            try {
                blockingExecutor.execute(this::pollAndEmit);
            } catch (RuntimeException failure) {
                running.set(false);
                fail(failure);
            }
        }

        private void pollAndEmit() {
            try {
                while (!cancelled.get() && demand.get() > 0) {
                    if (!pending.isEmpty()) {
                        emit(pending.removeFirst());
                        continue;
                    }
                    if (!initialized) {
                        cursor = query.currentCursor();
                        initialized = true;
                        pending.add(readyEvent(cursor));
                        continue;
                    }

                    EventObservationLiveBatch batch = query.pollAfter(cursor, criteria, configuration.getBatchSize());
                    cursor = batch.nextCursor();
                    for (ObservedEvent event : batch.events()) {
                        pending.add(observedEvent(event));
                    }
                    if (!pending.isEmpty()) {
                        continue;
                    }
                    if (keepaliveDue()) {
                        pending.add(keepaliveEvent(cursor));
                        continue;
                    }
                    break;
                }

                if (!cancelled.get() && demand.get() > 0) {
                    scheduleNextPoll();
                } else {
                    running.set(false);
                }
            } catch (Throwable failure) {
                running.set(false);
                fail(failure);
            }
        }

        private Event<EventObservationLiveEventResponse> readyEvent(long eventCursor) {
            return Event.of(new EventObservationLiveEventResponse(eventCursor, null))
                    .id(Long.toString(eventCursor))
                    .name("ready")
                    .retry(configuration.getReconnectDelay());
        }

        private Event<EventObservationLiveEventResponse> observedEvent(ObservedEvent observed) {
            return Event.of(new EventObservationLiveEventResponse(
                            observed.observationId(), ObservedEventResponse.from(observed)))
                    .id(Long.toString(observed.observationId()))
                    .name("event")
                    .retry(configuration.getReconnectDelay());
        }

        private Event<EventObservationLiveEventResponse> keepaliveEvent(long eventCursor) {
            return Event.of(new EventObservationLiveEventResponse(eventCursor, null))
                    .id(Long.toString(eventCursor))
                    .name("keepalive");
        }

        private boolean keepaliveDue() {
            return System.nanoTime() - lastEmissionNanos >= configuration.getKeepaliveInterval().toNanos();
        }

        private void emit(Event<EventObservationLiveEventResponse> event) {
            if (cancelled.get()) {
                return;
            }
            subscriber.onNext(event);
            lastEmissionNanos = System.nanoTime();
            demand.getAndUpdate(current -> current == Long.MAX_VALUE ? Long.MAX_VALUE : current - 1);
        }

        private void scheduleNextPoll() {
            ScheduledFuture<?> future = taskScheduler.schedule(configuration.getPollInterval(), () -> {
                scheduled.set(null);
                running.set(false);
                trigger();
            });
            ScheduledFuture<?> previous = scheduled.getAndSet(future);
            if (previous != null && previous != future) {
                previous.cancel(false);
            }
            if (cancelled.get()) {
                ScheduledFuture<?> cancelledFuture = scheduled.getAndSet(null);
                if (cancelledFuture != null) {
                    cancelledFuture.cancel(false);
                }
                running.set(false);
            }
        }

        private void fail(Throwable failure) {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            ScheduledFuture<?> future = scheduled.getAndSet(null);
            if (future != null) {
                future.cancel(false);
            }
            subscriber.onError(failure);
        }

        private static long addCap(long current, long increment) {
            long updated = current + increment;
            return updated < 0 ? Long.MAX_VALUE : updated;
        }
    }
}
