package io.signalharvester.results.event.kafka;

/** Publishes terminal Results consumer failures before their source offsets may be committed. */
public interface ResultsDeadLetterPublisher {

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
