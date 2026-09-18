package io.signalharvester.results.http;

import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.validation.Validated;
import io.signalharvester.results.application.DeadLetterRecovery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.Objects;

/** ADMIN transport for bounded inspection and deliberate Results dead-letter replay. */
@Validated
@Controller("/api/v1/admin/results/dead-letters")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ResultsDeadLetterRecoveryController {

    private final DeadLetterRecovery recovery;

    public ResultsDeadLetterRecoveryController(DeadLetterRecovery recovery) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
    }

    /** Inspects one real DLQ record without exposing its serialized source payload. */
    @Get("/{partition}/{offset}")
    public DeadLetterInspectionResponse inspect(
            @PathVariable @Min(0) int partition,
            @PathVariable @Min(0) long offset) {
        return DeadLetterInspectionResponse.from(recovery.inspect(partition, offset));
    }

    /** Reprocesses one explicitly confirmed DLQ record only through the Results-owned application path. */
    @Post("/{partition}/{offset}/replay")
    public DeadLetterInspectionResponse replay(
            @PathVariable @Min(0) int partition,
            @PathVariable @Min(0) long offset,
            @Body @Valid DeadLetterReplayRequest request) {
        Objects.requireNonNull(request, "request");
        return DeadLetterInspectionResponse.from(
                recovery.replay(partition, offset, request.expectedDeadLetterId()));
    }
}
