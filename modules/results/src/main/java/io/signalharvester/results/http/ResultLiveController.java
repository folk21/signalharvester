package io.signalharvester.results.http;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.sse.Event;
import io.micronaut.validation.Validated;
import io.signalharvester.results.application.ResultLiveCriteria;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.Optional;
import java.util.OptionalLong;
import org.reactivestreams.Publisher;

/** Public SSE adapter for resumable live analyzed-result updates. */
@Validated
@Controller("/api/v1/results/stream")
public class ResultLiveController {

    private final ResultSseStream stream;

    public ResultLiveController(ResultSseStream stream) {
        this.stream = stream;
    }

    /** Streams current analyzed-result updates and resumes after a numeric Last-Event-ID cursor. */
    @Get(produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<ResultLiveEventResponse>> stream(
            @QueryValue(defaultValue = "") String monitoringProfileId,
            @QueryValue(defaultValue = "") String sourceId,
            @QueryValue(defaultValue = "") String informationCategory,
            @Nullable @QueryValue Boolean relevant,
            @QueryValue(defaultValue = "") String classification,
            @Nullable @Header("Last-Event-ID") @PositiveOrZero Long lastEventId) {
        ResultLiveCriteria criteria = new ResultLiveCriteria(
                optional(monitoringProfileId),
                optional(sourceId),
                optional(informationCategory),
                Optional.ofNullable(relevant),
                optional(classification));
        OptionalLong cursor = lastEventId == null ? OptionalLong.empty() : OptionalLong.of(lastEventId);
        return stream.stream(criteria, cursor);
    }

    private static Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
