package io.signalharvester.analysis.application;

import java.util.List;
import java.util.Optional;

/** Internal read-only application boundary for operational inspection of analysis-owned claims. */
public interface AnalysisItemInspectionQuery {

    /** Returns recent durable claims with optional profile and source filters. */
    List<AnalysisItemInspection> recent(int limit, Optional<String> monitoringProfileId, Optional<String> sourceId);

    /** Returns one profile-scoped claim when present. */
    Optional<AnalysisItemInspection> find(String monitoringProfileId, String normalizedItemId);
}
