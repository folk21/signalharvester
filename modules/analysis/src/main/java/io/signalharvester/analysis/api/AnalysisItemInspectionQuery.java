package io.signalharvester.analysis.api;

import java.util.List;
import java.util.Optional;

/** Read-only application API for operational inspection of analysis-owned normalized-item claims. */
public interface AnalysisItemInspectionQuery {

    /** Returns recent durable claims with optional profile and source filters. */
    List<AnalysisItemInspection> recent(int limit, Optional<String> monitoringProfileId, Optional<String> sourceId);

    /** Returns one profile-scoped claim when present. */
    Optional<AnalysisItemInspection> find(String monitoringProfileId, String normalizedItemId);
}
