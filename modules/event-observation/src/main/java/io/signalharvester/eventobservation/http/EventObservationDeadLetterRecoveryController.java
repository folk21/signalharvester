package io.signalharvester.eventobservation.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.validation.Validated;
import io.signalharvester.eventobservation.application.DeadLetterRecovery;
import io.signalharvester.operations.api.OperationalChangeCategory;
import io.signalharvester.operations.api.OperationalChangeContext;
import io.signalharvester.operations.api.OperationalChangeJournal;
import io.signalharvester.operations.api.OperationalChangeOutcome;
import io.signalharvester.operations.api.OperationalChangeRequest;
import io.signalharvester.operations.api.OperationalChangeSource;
import io.signalharvester.operations.api.OperationalChangeTargetType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** ADMIN transport for bounded inspection and deliberate event-observation dead-letter replay. */
@Validated
@Controller("/api/v1/admin/event-observation/dead-letters")
@ExecuteOn(TaskExecutors.BLOCKING)
public class EventObservationDeadLetterRecoveryController {
    private static final Logger LOG = LoggerFactory.getLogger(EventObservationDeadLetterRecoveryController.class);

    private final DeadLetterRecovery recovery;
    private final OperationalChangeJournal changeJournal;

    public EventObservationDeadLetterRecoveryController(
            DeadLetterRecovery recovery, OperationalChangeJournal changeJournal) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.changeJournal = Objects.requireNonNull(changeJournal, "changeJournal");
    }

    /** Inspects one real DLQ record without exposing its serialized source payload. */
    @Get("/{partition}/{offset}")
    public DeadLetterInspectionResponse inspect(
            @PathVariable @Min(0) int partition,
            @PathVariable @Min(0) long offset) {
        return DeadLetterInspectionResponse.from(recovery.inspect(partition, offset));
    }

    /** Reprocesses one explicitly confirmed DLQ record through the Event Observation persistence. */
    @Post("/{partition}/{offset}/replay")
    public DeadLetterInspectionResponse replay(
            @PathVariable @Min(0) int partition,
            @PathVariable @Min(0) long offset,
            @Body @Valid @NotNull DeadLetterReplayRequest request,
            HttpRequest<?> httpRequest) {
        DeadLetterRecovery.Inspection inspection =
                recovery.replay(partition, offset, request.expectedDeadLetterId());
        recordReplayBestEffort(inspection, httpRequest);
        return DeadLetterInspectionResponse.from(inspection);
    }

    private void recordReplayBestEffort(DeadLetterRecovery.Inspection inspection, HttpRequest<?> request) {
        try {
            String actor = request.getUserPrincipal().map(Principal::getName).orElse("trusted-local");
            String correlationId = request.getHeaders().get("X-Request-ID");
            changeJournal.record(new OperationalChangeRequest(
                    OperationalChangeCategory.DEAD_LETTER_RECOVERY,
                    OperationalChangeTargetType.DEAD_LETTER_RECORD,
                    "event-observation:" + inspection.deadLetterPartition() + ":" + inspection.deadLetterOffset(),
                    Map.of(),
                    Map.of(
                            "action", "replayed",
                            "deadLetterId", inspection.deadLetterId(),
                            "sourceTopic", inspection.sourceTopic(),
                            "sourcePartition", Integer.toString(inspection.sourcePartition()),
                            "sourceOffset", Long.toString(inspection.sourceOffset())),
                    OperationalChangeOutcome.APPLIED,
                    new OperationalChangeContext(true, OperationalChangeSource.RECOVERY, actor, correlationId)));
        } catch (RuntimeException exception) {
            LOG.warn(
                    "Dead-letter replay succeeded but operational change journaling failed for event-observation partition={} offset={}",
                    inspection.deadLetterPartition(),
                    inspection.deadLetterOffset(),
                    exception);
        }
    }
}
