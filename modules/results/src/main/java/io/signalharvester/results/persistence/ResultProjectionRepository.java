package io.signalharvester.results.persistence;

import io.signalharvester.results.model.AnalyzedResult;
import io.signalharvester.results.model.RejectedResult;

/**
 * Internal persistence port for results-owned materialized analysis outcomes.
 */
public interface ResultProjectionRepository {

    void upsertAnalyzed(AnalyzedResult result);

    void upsertRejected(RejectedResult result);
}
