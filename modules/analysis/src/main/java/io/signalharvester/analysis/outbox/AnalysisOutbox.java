package io.signalharvester.analysis.outbox;

import io.signalharvester.analysis.event.AnalysisPublicationResult;
import io.signalharvester.analysis.event.AnalyzedItem;
import io.signalharvester.analysis.event.RejectedItem;

/** Records terminal Analysis integration events for transactional outbox delivery. */
public interface AnalysisOutbox {

    /** Records one analyzed terminal event in the caller-owned database transaction. */
    AnalysisPublicationResult enqueueAnalyzed(AnalyzedItem analyzedItem);

    /** Records one rejected terminal event in the caller-owned database transaction. */
    AnalysisPublicationResult enqueueRejected(RejectedItem rejectedItem);
}
