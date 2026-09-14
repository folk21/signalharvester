package io.signalharvester.results.application;

import java.util.List;

/** Bounded live-result poll outcome and the durable cursor covered by that poll. */
public record ResultLiveBatch(long nextCursor, List<ResultLiveUpdate> updates) {

    public ResultLiveBatch {
        if (nextCursor < 0) {
            throw new IllegalArgumentException("nextCursor must not be negative");
        }
        updates = List.copyOf(updates);
    }
}
