package io.signalharvester.analysis.persistence;

import io.signalharvester.analysis.api.AnalysisItemInspection;
import java.util.List;
import java.util.Optional;

/** Bounded read-only access to analysis-owned operational deduplication state. */
public interface AnalysisItemInspectionRepository {
    List<AnalysisItemInspection> findRecent(int limit, Optional<String> monitoringProfileId, Optional<String> sourceId);
    Optional<AnalysisItemInspection> find(String monitoringProfileId, String normalizedItemId);
}
