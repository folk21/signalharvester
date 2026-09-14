package io.signalharvester.eventobservation.http;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.validation.Validated;
import io.signalharvester.eventobservation.application.EventObservationCriteria;
import io.signalharvester.eventobservation.application.EventObservationQuery;
import io.signalharvester.eventobservation.application.EventObservationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Optional;

/** Read-only REST adapter for bounded technical event history. */
@Validated
@Controller("/api/v1/events")
@ExecuteOn(TaskExecutors.BLOCKING)
public class EventObservationController {

    private final EventObservationQuery query;

    public EventObservationController(EventObservationQuery query) {
        this.query = query;
    }

    /** Lists recent observed events using Event Explorer diagnostic filters. */
    @Get
    public List<ObservedEventResponse> recent(
            @QueryValue(defaultValue = "100")
            @Min(EventObservationService.MIN_LIMIT)
            @Max(EventObservationService.MAX_LIMIT) int limit,
            @QueryValue(defaultValue = "") String eventType,
            @QueryValue(defaultValue = "") String producer,
            @QueryValue(defaultValue = "") String topic,
            @QueryValue(defaultValue = "") String correlationId,
            @QueryValue(defaultValue = "") String collectionRunId,
            @QueryValue(defaultValue = "") String itemId,
            @QueryValue(defaultValue = "") String traceId) {
        return query.recent(criteria(eventType, producer, topic, correlationId, collectionRunId, itemId, traceId), limit)
                .stream()
                .map(ObservedEventResponse::from)
                .toList();
    }

    static EventObservationCriteria criteria(
            String eventType,
            String producer,
            String topic,
            String correlationId,
            String collectionRunId,
            String itemId,
            String traceId) {
        return new EventObservationCriteria(
                optional(eventType), optional(producer), optional(topic), optional(correlationId),
                optional(collectionRunId), optional(itemId), optional(traceId));
    }

    private static Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
