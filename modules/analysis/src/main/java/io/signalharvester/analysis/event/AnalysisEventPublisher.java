package io.signalharvester.analysis.event;

/**
 * Asynchronous integration boundary for terminal analysis outcomes.
 */
public interface AnalysisEventPublisher {

    /** Publishes one valid normalized item's analysis outcome and waits for Kafka acknowledgement. */
    AnalysisPublicationResult publishAnalyzed(AnalyzedItem analyzedItem);

    /** Publishes one intentional analysis rejection and waits for Kafka acknowledgement. */
    AnalysisPublicationResult publishRejected(RejectedItem rejectedItem);
}
