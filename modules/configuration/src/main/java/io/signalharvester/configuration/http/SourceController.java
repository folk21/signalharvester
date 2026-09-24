package io.signalharvester.configuration.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.validation.Validated;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.application.SourceConfigurationOperations;
import io.signalharvester.operations.api.OperationalChangeContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Implements the source configuration REST contract through configuration-owned blocking use cases.
 */
@Validated
@Controller("/api/v1/sources")
@ExecuteOn(TaskExecutors.BLOCKING)
public class SourceController {

    private final SourceConfigurationOperations operations;

    public SourceController(SourceConfigurationOperations operations) {
        this.operations = operations;
    }

    /** Returns all configured sources. */
    @Get
    public List<SourceResponse> listSources() {
        return operations.list().stream().map(SourceResponse::from).toList();
    }

    /** Creates and persists a configured source. */
    @Post
    public HttpResponse<SourceResponse> createSource(
            @Body @Valid @NotNull SourceUpsertRequest request,
            HttpRequest<?> httpRequest) {
        SourceResponse response = SourceResponse.from(
                operations.create(request.toCommand(), changeContext(httpRequest)));
        return HttpResponse.created(response);
    }

    /** Returns one configured source or maps a missing identifier to HTTP 404. */
    @Get("/{sourceId}")
    public SourceResponse getSource(@PathVariable UUID sourceId) {
        return SourceResponse.from(operations.get(SourceId.of(sourceId)));
    }

    /** Replaces an existing configured source and its settings. */
    @Put("/{sourceId}")
    public SourceResponse updateSource(
            @PathVariable UUID sourceId,
            @Body @Valid @NotNull SourceUpsertRequest request,
            HttpRequest<?> httpRequest) {
        return SourceResponse.from(operations.update(
                SourceId.of(sourceId), request.toCommand(), changeContext(httpRequest)));
    }

    /** Deletes an existing configured source. */
    @Delete("/{sourceId}")
    public HttpResponse<?> deleteSource(@PathVariable UUID sourceId, HttpRequest<?> httpRequest) {
        operations.delete(SourceId.of(sourceId), changeContext(httpRequest));
        return HttpResponse.noContent();
    }

    private static OperationalChangeContext changeContext(HttpRequest<?> request) {
        String actor = request.getUserPrincipal().map(java.security.Principal::getName).orElse("trusted-local");
        return OperationalChangeContext.rest(actor, request.getHeaders().get("X-Request-ID"));
    }
}
