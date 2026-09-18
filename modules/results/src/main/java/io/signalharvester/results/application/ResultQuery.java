package io.signalharvester.results.application;

import java.util.Optional;

/** Internal read-only application boundary for browsing Results-owned analyzed projections. */
public interface ResultQuery {

    /** Returns one bounded page matching the supplied filters, search expression, and optional cursor. */
    ResultPage browse(ResultQueryCriteria criteria);

    /** Returns one profile-scoped analyzed result with full persisted detail when present. */
    Optional<ResultDetail> find(String monitoringProfileId, String normalizedItemId);
}
