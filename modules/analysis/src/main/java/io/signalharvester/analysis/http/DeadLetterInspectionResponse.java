package io.signalharvester.analysis.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.analysis.application.DeadLetterRecovery;

/** Sanitized administrative representation of one Analysis dead-letter record. */
@Serdeable
public record DeadLetterInspectionResponse(
        String deadLetterId,
        String consumer,
        String consumerGroup,
        String deadLetterTopic,
        int deadLetterPartition,
        long deadLetterOffset,
        String sourceTopic,
        int sourcePartition,
        long sourceOffset,
        String sourceKey,
        String failureType,
        String failureMessage,
        int attempts,
        boolean retryable,
        int sourcePayloadBytes) {

    static DeadLetterInspectionResponse from(DeadLetterRecovery.Inspection inspection) {
        return new DeadLetterInspectionResponse(
                inspection.deadLetterId(),
                inspection.consumer(),
                inspection.consumerGroup(),
                inspection.deadLetterTopic(),
                inspection.deadLetterPartition(),
                inspection.deadLetterOffset(),
                inspection.sourceTopic(),
                inspection.sourcePartition(),
                inspection.sourceOffset(),
                inspection.sourceKey(),
                inspection.failureType(),
                inspection.failureMessage(),
                inspection.attempts(),
                inspection.retryable(),
                inspection.sourcePayloadBytes());
    }
}
