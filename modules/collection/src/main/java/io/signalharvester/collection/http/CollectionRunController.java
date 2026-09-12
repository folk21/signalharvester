package io.signalharvester.collection.http;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.validation.Validated;
import io.signalharvester.collection.api.CollectionRunHistory;
import io.signalharvester.collection.api.CollectionRunner;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;

/** Operational REST API for manually starting and inspecting collection runs. */
@Validated
@Controller("/api/v1/admin/collection-runs")
@ExecuteOn(TaskExecutors.BLOCKING)
public class CollectionRunController {
    private final CollectionRunner runner;
    private final CollectionRunHistory history;

    public CollectionRunController(CollectionRunner runner, CollectionRunHistory history) {
        this.runner = runner;
        this.history = history;
    }

    /** Starts one synchronous best-effort collection run and returns its durable terminal state. */
    @Post
    public HttpResponse<CollectionRunResponse> start(@Body @Valid CollectionRunRequestPayload request) {
        return HttpResponse.created(CollectionRunResponse.from(runner.run(request.toRunRequest())));
    }

    /** Returns the most recent completed collection runs, newest first. */
    @Get
    public List<CollectionRunResponse> recent(
            @QueryValue(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return history.recent(limit).stream().map(CollectionRunResponse::from).toList();
    }

    /** Returns one durable collection-run snapshot. */
    @Get("/{collectionRunId}")
    public CollectionRunResponse get(@PathVariable UUID collectionRunId) {
        return CollectionRunResponse.from(history.get(collectionRunId.toString()));
    }
}
