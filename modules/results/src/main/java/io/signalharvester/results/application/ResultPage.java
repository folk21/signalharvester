package io.signalharvester.results.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One bounded Results browsing page plus an opaque cursor for the next page when more rows exist. */
public record ResultPage(List<ResultSummary> results, Optional<String> nextCursor) {

    public ResultPage {
        results = List.copyOf(Objects.requireNonNull(results, "results"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
    }
}
