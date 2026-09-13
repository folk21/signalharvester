package io.signalharvester.results.application;

import java.util.List;
import java.util.Optional;

/**
 * Internal read-only application boundary for browsing Results-owned analyzed projections.
 */
public interface ResultQuery {

    /** Returns recent analyzed results matching the supplied bounded filters. */
    List<ResultSummary> recent(ResultQueryCriteria criteria);

    /** Returns one profile-scoped analyzed result with full persisted detail when present. */
    Optional<ResultDetail> find(String monitoringProfileId, String normalizedItemId);
}
