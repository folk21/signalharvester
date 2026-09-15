package io.signalharvester.eventobservation.event.kafka;

/** Publishes terminal Event Observation consumer failures before source offsets may be committed. */
public interface EventObservationDeadLetterPublisher {

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
