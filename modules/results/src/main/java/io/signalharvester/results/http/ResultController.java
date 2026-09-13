package io.signalharvester.results.http;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.validation.Validated;
import io.signalharvester.results.application.ResultQuery;
import io.signalharvester.results.application.ResultQueryCriteria;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Public read-only REST API for browsing durable analyzed Results projections. */
@Validated
@Controller("/api/v1/results")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ResultController {

    private final ResultQuery query;

    public ResultController(ResultQuery query) {
        this.query = query;
    }

    /** Returns recent analyzed results with bounded product-facing filters. */
    @Get
    public List<ResultSummaryResponse> recent(
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
                Optional.ofNullable(analyzedTo));
        return query.recent(criteria).stream().map(ResultSummaryResponse::from).toList();
    }

    /** Returns one profile-scoped analyzed result including content, attributes, and provenance. */
    @Get("/{normalizedItemId}")
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
