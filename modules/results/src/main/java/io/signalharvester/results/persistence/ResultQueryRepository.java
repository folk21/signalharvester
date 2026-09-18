package io.signalharvester.results.persistence;

import io.signalharvester.results.application.ResultDetail;
import io.signalharvester.results.application.ResultPagePosition;
import io.signalharvester.results.application.ResultQueryCriteria;
import io.signalharvester.results.application.ResultSummary;
import java.util.List;
import java.util.Optional;

/** Internal persistence port for bounded reads from the Results analyzed projection. */
public interface ResultQueryRepository {

    /** Returns at most {@code fetchLimit} rows after the optional deterministic keyset position. */
    List<ResultSummary> findPage(ResultQueryCriteria criteria, Optional<ResultPagePosition> after, int fetchLimit);

    Optional<ResultDetail> find(String monitoringProfileId, String normalizedItemId);
}
