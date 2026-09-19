package io.signalharvester.analysis.http;

import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.validation.Validated;
import io.signalharvester.analysis.application.DeadLetterRecovery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;

/** ADMIN transport for bounded inspection and deliberate Analysis dead-letter replay. */
@Validated
@Controller("/api/v1/admin/analysis/dead-letters")
@ExecuteOn(TaskExecutors.BLOCKING)
public class AnalysisDeadLetterRecoveryController {

    private final DeadLetterRecovery recovery;

    public AnalysisDeadLetterRecoveryController(DeadLetterRecovery recovery) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
    }

    /** Inspects one real DLQ record without exposing its serialized source payload. */
    @Get("/{partition}/{offset}")
    public DeadLetterInspectionResponse inspect(
            @PathVariable @Min(0) int partition,
            @PathVariable @Min(0) long offset) {
        return DeadLetterInspectionResponse.from(recovery.inspect(partition, offset));
    }

    /** Reprocesses one explicitly confirmed DLQ record only through the Analysis-owned application path. */
    @Post("/{partition}/{offset}/replay")
    public DeadLetterInspectionResponse replay(
            @PathVariable @Min(0) int partition,
            @PathVariable @Min(0) long offset,
            @Body @Valid @NotNull DeadLetterReplayRequest request) {
        return DeadLetterInspectionResponse.from(
                recovery.replay(partition, offset, request.expectedDeadLetterId()));
    }
}
