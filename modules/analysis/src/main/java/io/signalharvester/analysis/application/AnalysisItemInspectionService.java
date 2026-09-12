package io.signalharvester.analysis.application;

import io.signalharvester.analysis.api.AnalysisItemInspection;
import io.signalharvester.analysis.api.AnalysisItemInspectionQuery;
import io.signalharvester.analysis.persistence.AnalysisItemInspectionRepository;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Application implementation of the bounded operational analysis inspection API. */
@Singleton
public final class AnalysisItemInspectionService implements AnalysisItemInspectionQuery {

    private final AnalysisItemInspectionRepository repository;

    public AnalysisItemInspectionService(AnalysisItemInspectionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public List<AnalysisItemInspection> recent(
            int limit,
            Optional<String> monitoringProfileId,
            Optional<String> sourceId) {
        return repository.findRecent(limit, monitoringProfileId, sourceId);
    }

    @Override
    public Optional<AnalysisItemInspection> find(String monitoringProfileId, String normalizedItemId) {
        return repository.find(monitoringProfileId, normalizedItemId);
    }
}
