package io.signalharvester.results.http;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.validation.Validated;
import io.signalharvester.results.application.ResultPage;
import io.signalharvester.results.application.ResultQuery;
import io.signalharvester.results.application.ResultQueryCriteria;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Public read-only REST API for browsing durable analyzed Results projections. */
@Validated
@Controller("/api/v1/results")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ResultController {

    public static final String NEXT_CURSOR_HEADER = "X-Next-Cursor";

    private final ResultQuery query;

    public ResultController(ResultQuery query) {
        this.query = query;
    }

    /** Returns one backward-compatible result array and an optional opaque next-page cursor header. */
    @Get
    public HttpResponse<List<ResultSummaryResponse>> recent(
            @QueryValue(defaultValue = "") @Size(max = ResultQueryCriteria.MAX_CURSOR_LENGTH) String cursor,
            @QueryValue(defaultValue = "") @Size(max = ResultQueryCriteria.MAX_SEARCH_LENGTH) String search,
            @QueryValue(defaultValue = "50")
            @Min(ResultQueryCriteria.MIN_LIMIT)
            @Max(ResultQueryCriteria.MAX_LIMIT) int limit,
            @QueryValue(defaultValue = "") String monitoringProfileId,
            @QueryValue(defaultValue = "") String sourceId,
            @QueryValue(defaultValue = "") String informationCategory,
            @Nullable @QueryValue Boolean relevant,
            @QueryValue(defaultValue = "") String classification,
            @Nullable @QueryValue Instant analyzedFrom,
            @Nullable @QueryValue Instant analyzedTo) {
        ResultQueryCriteria criteria = new ResultQueryCriteria(
                limit,
                optional(monitoringProfileId),
                optional(sourceId),
                optional(informationCategory),
                Optional.ofNullable(relevant),
                optional(classification),
                Optional.ofNullable(analyzedFrom),
                Optional.ofNullable(analyzedTo),
                optional(search),
                optional(cursor));
        ResultPage page = query.browse(criteria);
        MutableHttpResponse<List<ResultSummaryResponse>> response = HttpResponse.ok(
                page.results().stream().map(ResultSummaryResponse::from).toList());
        page.nextCursor().ifPresent(value -> response.header(NEXT_CURSOR_HEADER, value));
        return response;
    }

    /** Returns one profile-scoped analyzed result including content, attributes, and provenance. */
    @Get("/{normalizedItemId:[0-9a-f]+}")
    public HttpResponse<ResultDetailResponse> get(
            @PathVariable @Pattern(regexp = "[0-9a-f]{64}") String normalizedItemId,
            @QueryValue @NotBlank String monitoringProfileId) {
        return query.find(monitoringProfileId, normalizedItemId)
                .map(ResultDetailResponse::from)
                .map(HttpResponse::ok)
                .orElseGet(HttpResponse::notFound);
    }

    private static Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
