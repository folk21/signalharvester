package io.signalharvester.results.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** JSON data payload carried by Results SSE events. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable
public record ResultLiveEventResponse(long cursor, @Nullable ResultSummaryResponse result) {

    public ResultLiveEventResponse {
        if (cursor < 0) {
            throw new IllegalArgumentException("cursor must not be negative");
        }
    }
}
