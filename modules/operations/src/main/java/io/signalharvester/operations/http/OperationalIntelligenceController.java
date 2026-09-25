package io.signalharvester.operations.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.validation.Validated;
import io.signalharvester.operations.api.OperationalChangeCategory;
import io.signalharvester.operations.api.OperationalChangeContext;
import io.signalharvester.operations.api.OperationalChangeJournal;
import io.signalharvester.operations.api.OperationalChangeOutcome;
import io.signalharvester.operations.api.OperationalChangeRequest;
import io.signalharvester.operations.api.OperationalChangeSource;
import io.signalharvester.operations.api.OperationalChangeTargetType;
import io.signalharvester.operations.application.OperationalIntelligenceOperations;
import io.signalharvester.operations.assisted.AssistedInvestigationOperations;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** ADMIN HTTP boundary for operational change history and deterministic/statistical health intelligence. */
@Validated
@Controller("/api/v1/admin/operations")
@ExecuteOn(TaskExecutors.BLOCKING)
public class OperationalIntelligenceController {
    private static final Set<String> SENSITIVE_KEY_PARTS = Set.of(
            "password", "secret", "token", "authorization", "credential", "cookie", "apikey", "api_key");

    private final OperationalIntelligenceOperations operations;
    private final AssistedInvestigationOperations assistedInvestigation;
    private final OperationalChangeJournal changeJournal;

    public OperationalIntelligenceController(
            OperationalIntelligenceOperations operations,
            AssistedInvestigationOperations assistedInvestigation,
            OperationalChangeJournal changeJournal) {
        this.operations = operations;
        this.assistedInvestigation = assistedInvestigation;
        this.changeJournal = changeJournal;
    }

    /** Returns the most recent bounded operational changes. */
    @Get("/changes")
    public List<OperationalChangeResponse> changes(
            @QueryValue(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return operations.recentChanges(limit).stream().map(OperationalChangeResponse::from).toList();
    }

    /** Records an explicit safe operational marker from repository-owned tooling or scenarios. */
    @Post("/changes/markers")
    public HttpResponse<OperationalChangeResponse> marker(
            @Body @Valid @NotNull OperationalMarkerRequest request,
            HttpRequest<?> httpRequest) {
        boolean deploymentMarker =
                request.category() == OperationalChangeCategory.DEPLOYMENT_TUNING
                        && request.targetType() == OperationalChangeTargetType.DEPLOYMENT;
        boolean scenarioMarker =
                request.category() == OperationalChangeCategory.TEST_SCENARIO
                        && request.targetType() == OperationalChangeTargetType.SCENARIO;
        if (!deploymentMarker && !scenarioMarker) {
            throw new InvalidOperationalMarkerException(
                    "Operational marker category and target type must form a supported pair");
        }
        Map<String, String> details = sanitizeMarkerDetails(request.details());
        OperationalChangeContext context = context(httpRequest, OperationalChangeSource.TOOLING);
        var change = changeJournal.record(new OperationalChangeRequest(
                request.category(), request.targetType(), request.targetId(), Map.of(), details,
                OperationalChangeOutcome.APPLIED, context));
        return HttpResponse.created(OperationalChangeResponse.from(change));
    }

    /** Captures and persists the current deterministic/statistical Health Snapshot. */
    @Post("/health/snapshots")
    public HttpResponse<HealthSnapshotResponse> captureSnapshot() {
        return HttpResponse.created(HealthSnapshotResponse.from(operations.captureHealthSnapshot()));
    }

    /** Returns the latest persisted Health Snapshot. */
    @Get("/health/snapshots/latest")
    public HealthSnapshotResponse latestSnapshot() {
        return HealthSnapshotResponse.from(operations.latestSnapshot());
    }

    /** Returns the latest bounded Health Report in Markdown form for manual human/LLM analysis. */
    @Get("/health/reports/latest")
    @Produces(MediaType.TEXT_PLAIN)
    public HttpResponse<String> latestReport() {
        return HttpResponse.ok(operations.latestMarkdownReport()).contentType(MediaType.TEXT_PLAIN_TYPE);
    }

    /** Returns a bounded sanitized prompt package for manual local/external LLM analysis. */
    @Get("/health/analysis-packages/latest")
    public HealthAnalysisPackageResponse latestAnalysisPackage() {
        return HealthAnalysisPackageResponse.from(assistedInvestigation.latestAnalysisPackage());
    }

    /** Persists one validated structured assessment produced outside SignalHarvester. */
    @Post("/health/assessments/manual")
    public HttpResponse<IncidentAssessmentResponse> submitManualAssessment(
            @Body @Valid @NotNull IncidentAssessmentSubmissionRequest request) {
        return HttpResponse.created(IncidentAssessmentResponse.from(
                assistedInvestigation.submitManual(request.snapshotId(), request.toDraft())));
    }

    /** Explicitly invokes the configured provider for the latest Health Snapshot; never scheduled automatically. */
    @Post("/health/assessments/analyze-latest")
    public HttpResponse<IncidentAssessmentResponse> analyzeLatest() {
        return HttpResponse.created(IncidentAssessmentResponse.from(assistedInvestigation.analyzeLatest()));
    }

    /** Returns recent persisted validated assessments. */
    @Get("/health/assessments")
    public List<IncidentAssessmentResponse> assessments(
            @QueryValue(defaultValue = "20") @Min(1) @Max(200) int limit) {
        return assistedInvestigation.recentAssessments(limit).stream()
                .map(IncidentAssessmentResponse::from)
                .toList();
    }

    /** Returns nearest persisted snapshots before and after the selected change. */
    @Get("/changes/{changeId}/health-correlation")
    public OperationalHealthCorrelationResponse correlate(@PathVariable UUID changeId) {
        return OperationalHealthCorrelationResponse.from(operations.correlateChange(changeId));
    }

    private static OperationalChangeContext context(HttpRequest<?> request, OperationalChangeSource source) {
        String actor = request.getUserPrincipal().map(Principal::getName).orElse("trusted-local");
        String correlation = request.getHeaders().get("X-Request-ID");
        return new OperationalChangeContext(true, source, actor, correlation);
    }

    private static Map<String, String> sanitizeMarkerDetails(Map<String, String> details) {
        for (String key : details.keySet()) {
            String normalized = key.toLowerCase(Locale.ROOT);
            if (SENSITIVE_KEY_PARTS.stream().anyMatch(normalized::contains)) {
                throw new InvalidOperationalMarkerException("Operational marker contains a sensitive detail key: " + key);
            }
        }
        return details;
    }
}
