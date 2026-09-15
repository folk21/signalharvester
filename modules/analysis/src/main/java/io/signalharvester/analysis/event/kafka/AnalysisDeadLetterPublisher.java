package io.signalharvester.analysis.event.kafka;

/** Publishes terminal Analysis consumer failures before their source offsets may be committed. */
public interface AnalysisDeadLetterPublisher {

    /** Publishes one failed source record with bounded diagnostic metadata. */
    void publish(
            String sourceTopic,
            int sourcePartition,
            long sourceOffset,
            String sourceKey,
            byte[] sourcePayload,
            RuntimeException failure,
            int attempts,
            boolean retryable);
}
