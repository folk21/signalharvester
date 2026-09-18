package io.signalharvester.results.application;

/** Internal operator boundary for inspecting and replaying Results dead-letter records. */
public interface DeadLetterRecovery {

    /** Loads one Results dead-letter record by its Kafka DLQ position. */
    Inspection inspect(int deadLetterPartition, long deadLetterOffset);

    /** Reprocesses one confirmed dead-letter record through the Results-owned application path. */
    Inspection replay(int deadLetterPartition, long deadLetterOffset, String expectedDeadLetterId);

    /** Sanitized dead-letter metadata returned to administrative HTTP callers. */
    record Inspection(
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
    }
}
