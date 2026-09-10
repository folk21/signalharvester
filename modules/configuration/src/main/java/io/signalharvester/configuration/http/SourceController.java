package io.signalharvester.configuration.http;

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
import io.signalharvester.configuration.application.SourceConfigurationManager;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

/**
 * Implements the source configuration REST contract through configuration-owned blocking use cases.
 */
@Validated
@Controller("/api/v1/sources")
@ExecuteOn(TaskExecutors.BLOCKING)
public class SourceController {

    private final SourceConfigurationManager manager;

    public SourceController(SourceConfigurationManager manager) {
        this.manager = manager;
    }

    /** Returns all configured sources. */
    @Get
    public List<SourceResponse> listSources() {
        return manager.list().stream().map(SourceResponse::from).toList();
    }

    /** Creates and persists a configured source. */
    @Post
    public HttpResponse<SourceResponse> createSource(@Body @Valid SourceUpsertRequest request) {
        SourceResponse response = SourceResponse.from(manager.create(request.toCommand()));
        return HttpResponse.created(response);
    }

    /** Returns one configured source or maps a missing identifier to HTTP 404. */
    @Get("/{sourceId}")
    public SourceResponse getSource(@PathVariable UUID sourceId) {
        return SourceResponse.from(manager.get(SourceId.of(sourceId)));
    }

    /** Replaces an existing configured source and its settings. */
    @Put("/{sourceId}")
    public SourceResponse updateSource(
            @PathVariable UUID sourceId,
            @Body @Valid SourceUpsertRequest request) {
        return SourceResponse.from(manager.update(SourceId.of(sourceId), request.toCommand()));
    }

    /** Deletes an existing configured source. */
    @Delete("/{sourceId}")
    public HttpResponse<?> deleteSource(@PathVariable UUID sourceId) {
        manager.delete(SourceId.of(sourceId));
        return HttpResponse.noContent();
    }
}
