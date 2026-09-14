package io.signalharvester.eventobservation.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** JSON data payload carried by Event Explorer SSE events. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable
public record EventObservationLiveEventResponse(long cursor, @Nullable ObservedEventResponse event) {
    public EventObservationLiveEventResponse {
        if (cursor < 0) {
            throw new IllegalArgumentException("cursor must not be negative");
        }
    }
}
