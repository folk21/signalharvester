package io.signalharvester.eventobservation.http;

import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.TaskScheduler;
import io.signalharvester.common.concurrent.DemandDrivenPollingLoop;
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
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ExecutorService;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Creates backpressure-aware Event Explorer SSE publishers over durable observation history.
 * Generic demand, scheduling, and cancellation lifecycle is delegated to the shared polling utility.
 */
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
        private final Deque<Event<EventObservationLiveEventResponse>> pending = new ArrayDeque<>();
        private final DemandDrivenPollingLoop<Event<EventObservationLiveEventResponse>> pollingLoop;

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

        private Optional<Event<EventObservationLiveEventResponse>> nextEvent() {
            if (!pending.isEmpty()) {
                return Optional.of(pending.removeFirst());
            }
            if (!initialized) {
                cursor = query.currentCursor();
                initialized = true;
                return Optional.of(readyEvent(cursor));
            }

            EventObservationLiveBatch batch = query.pollAfter(cursor, criteria, configuration.getBatchSize());
            cursor = batch.nextCursor();
            for (ObservedEvent event : batch.events()) {
                pending.add(observedEvent(event));
            }
            if (!pending.isEmpty()) {
                return Optional.of(pending.removeFirst());
            }
            if (keepaliveDue()) {
                return Optional.of(keepaliveEvent(cursor));
            }
            return Optional.empty();
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
            if (pollingLoop.isCancelled()) {
                return;
            }
            subscriber.onNext(event);
            lastEmissionNanos = System.nanoTime();
        }
    }
}
