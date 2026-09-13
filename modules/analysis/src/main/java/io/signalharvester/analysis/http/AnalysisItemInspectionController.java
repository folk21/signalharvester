package io.signalharvester.analysis.http;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.validation.Validated;
import io.signalharvester.analysis.application.AnalysisItemInspectionQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Optional;

/** Read-only operational REST API for analysis-owned deduplication state. */
@Validated
@Controller("/api/v1/admin/analysis/items")
@ExecuteOn(TaskExecutors.BLOCKING)
public class AnalysisItemInspectionController {
    private final AnalysisItemInspectionQuery query;

    public AnalysisItemInspectionController(AnalysisItemInspectionQuery query) {
        this.query = query;
    }

    /** Returns recent normalized-item claims with optional profile/source filters. */
    @Get
    public List<AnalysisItemInspectionResponse> recent(
            @QueryValue(defaultValue = "50") @Min(1) @Max(200) int limit,
            @QueryValue(defaultValue = "") String monitoringProfileId,
            @QueryValue(defaultValue = "") String sourceId) {
        return query.recent(limit, optional(monitoringProfileId), optional(sourceId)).stream()
                .map(AnalysisItemInspectionResponse::from)
                .toList();
    }

    /** Returns one profile-scoped normalized-item claim when present. */
    @Get("/{normalizedItemId}")
    public HttpResponse<AnalysisItemInspectionResponse> get(
            @PathVariable @Pattern(regexp = "[0-9a-f]{64}") String normalizedItemId,
            @QueryValue @NotBlank String monitoringProfileId) {
        return query.find(monitoringProfileId, normalizedItemId)
                .map(AnalysisItemInspectionResponse::from)
                .map(HttpResponse::ok)
                .orElseGet(HttpResponse::notFound);
    }

    private static Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
