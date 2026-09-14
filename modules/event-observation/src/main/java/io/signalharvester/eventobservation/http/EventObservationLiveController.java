package io.signalharvester.eventobservation.http;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.sse.Event;
import io.micronaut.validation.Validated;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.OptionalLong;
import org.reactivestreams.Publisher;

/** Live SSE adapter for bounded technical Event Explorer updates. */
@Validated
@Controller("/api/v1/events/stream")
public class EventObservationLiveController {

    private final EventObservationSseStream stream;

    public EventObservationLiveController(EventObservationSseStream stream) {
        this.stream = stream;
    }

    /** Streams observed events after an optional durable Last-Event-ID cursor. */
    @Get(produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<EventObservationLiveEventResponse>> stream(
            @QueryValue(defaultValue = "") String eventType,
            @QueryValue(defaultValue = "") String producer,
            @QueryValue(defaultValue = "") String topic,
            @QueryValue(defaultValue = "") String correlationId,
            @QueryValue(defaultValue = "") String collectionRunId,
            @QueryValue(defaultValue = "") String itemId,
            @QueryValue(defaultValue = "") String traceId,
            @Nullable @Header("Last-Event-ID") @PositiveOrZero Long lastEventId) {
        OptionalLong cursor = lastEventId == null ? OptionalLong.empty() : OptionalLong.of(lastEventId);
        return stream.stream(
                EventObservationController.criteria(
                        eventType, producer, topic, correlationId, collectionRunId, itemId, traceId),
                cursor);
    }
}
