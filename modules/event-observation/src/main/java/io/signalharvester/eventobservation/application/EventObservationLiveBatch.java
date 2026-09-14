package io.signalharvester.eventobservation.application;

import io.signalharvester.eventobservation.model.ObservedEvent;
import java.util.List;
import java.util.Objects;

/** One bounded live-event poll plus the durable cursor reached by that poll. */
public record EventObservationLiveBatch(long nextCursor, List<ObservedEvent> events) {
    public EventObservationLiveBatch {
        if (nextCursor < 0) {
            throw new IllegalArgumentException("nextCursor must not be negative");
        }
        events = List.copyOf(Objects.requireNonNull(events, "events"));
    }
}
