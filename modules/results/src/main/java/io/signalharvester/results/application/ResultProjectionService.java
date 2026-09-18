package io.signalharvester.results.application;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.results.model.AnalyzedResult;
import io.signalharvester.results.model.RejectedResult;
import io.signalharvester.results.persistence.ResultProjectionRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.util.Objects;

/**
 * Owns the application transaction for idempotent analysis-outcome projection into Results persistence.
 */
@Singleton
public final class ResultProjectionService implements AnalysisOutcomeProjector {

    private final ResultProjectionRepository repository;
    private final TransactionOperations<Connection> transactions;

    public ResultProjectionService(
            ResultProjectionRepository repository,
            @Named("default") TransactionOperations<Connection> transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public void projectAnalyzed(AnalyzedResult result) {
        Objects.requireNonNull(result, "result");
        transactions.executeWrite(status -> {
            repository.upsertAnalyzed(result);
            return null;
        });
    }

    @Override
    public void projectRejected(RejectedResult result) {
        Objects.requireNonNull(result, "result");
        transactions.executeWrite(status -> {
            repository.upsertRejected(result);
            return null;
        });
    }
}
