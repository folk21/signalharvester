package io.signalharvester.results.persistence;

import io.signalharvester.results.application.ResultDetail;
import io.signalharvester.results.application.ResultQueryCriteria;
import io.signalharvester.results.application.ResultSummary;
import java.util.List;
import java.util.Optional;

/**
 * Internal persistence port for bounded reads from the Results analyzed projection.
 */
public interface ResultQueryRepository {

    List<ResultSummary> findRecent(ResultQueryCriteria criteria);

    Optional<ResultDetail> find(String monitoringProfileId, String normalizedItemId);
}
