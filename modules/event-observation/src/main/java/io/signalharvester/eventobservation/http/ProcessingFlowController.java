package io.signalharvester.eventobservation.http;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.validation.Validated;
import io.signalharvester.eventobservation.application.ProcessingFlowQuery;
import jakarta.validation.constraints.NotBlank;

/** Read-only HTTP adapter for reconstructed Event Explorer processing graphs. */
@Validated
@Controller("/api/v1/flows")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ProcessingFlowController {

    private final ProcessingFlowQuery query;

    public ProcessingFlowController(ProcessingFlowQuery query) {
        this.query = query;
    }

    /** Reconstructs the bounded processing graph for one collection run. */
    @Get("/collection-runs/{collectionRunId}")
    public ProcessingFlowResponse collectionRun(@PathVariable @NotBlank String collectionRunId) {
        return ProcessingFlowResponse.from(query.collectionRun(collectionRunId));
    }

    /** Reconstructs one raw/normalized item branch within a collection run. */
    @Get("/collection-runs/{collectionRunId}/items/{itemId}")
    public ProcessingFlowResponse item(
            @PathVariable @NotBlank String collectionRunId,
            @PathVariable @NotBlank String itemId) {
        return ProcessingFlowResponse.from(query.item(collectionRunId, itemId));
    }
}
