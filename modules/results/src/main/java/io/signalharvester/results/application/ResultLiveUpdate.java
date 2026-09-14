package io.signalharvester.results.application;

import java.util.Objects;

/** One current analyzed-result projection associated with a monotonic live-delivery cursor. */
public record ResultLiveUpdate(long eventId, ResultSummary result) {

    public ResultLiveUpdate {
        if (eventId <= 0) {
            throw new IllegalArgumentException("eventId must be positive");
        }
        Objects.requireNonNull(result, "result");
    }
}
