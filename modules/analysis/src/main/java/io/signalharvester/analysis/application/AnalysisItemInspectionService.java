package io.signalharvester.analysis.application;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.analysis.api.AnalysisItemInspection;
import io.signalharvester.analysis.api.AnalysisItemInspectionQuery;
import io.signalharvester.analysis.persistence.AnalysisItemInspectionRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Application implementation of the bounded operational analysis inspection API.
 * Each inspection call owns a short read-only JDBC transaction.
 */
@Singleton
public final class AnalysisItemInspectionService implements AnalysisItemInspectionQuery {

    private final AnalysisItemInspectionRepository repository;
    private final TransactionOperations<Connection> transactions;

    public AnalysisItemInspectionService(
            AnalysisItemInspectionRepository repository,
            @Named("default") TransactionOperations<Connection> transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public List<AnalysisItemInspection> recent(
            int limit,
            Optional<String> monitoringProfileId,
            Optional<String> sourceId) {
        return transactions.executeRead(status -> repository.findRecent(limit, monitoringProfileId, sourceId));
    }

    @Override
    public Optional<AnalysisItemInspection> find(String monitoringProfileId, String normalizedItemId) {
        return transactions.executeRead(status -> repository.find(monitoringProfileId, normalizedItemId));
    }
}
