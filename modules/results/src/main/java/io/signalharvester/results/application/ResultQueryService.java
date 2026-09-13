package io.signalharvester.results.application;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.results.persistence.ResultQueryRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Owns short read-only JDBC transactions for Results browsing and detail queries.
 */
@Singleton
public final class ResultQueryService implements ResultQuery {

    private final ResultQueryRepository repository;
    private final TransactionOperations<Connection> transactions;

    public ResultQueryService(
            ResultQueryRepository repository,
            @Named("default") TransactionOperations<Connection> transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public List<ResultSummary> recent(ResultQueryCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria");
        return transactions.executeRead(status -> repository.findRecent(criteria));
    }

    @Override
    public Optional<ResultDetail> find(String monitoringProfileId, String normalizedItemId) {
        requireNonBlank(monitoringProfileId, "monitoringProfileId");
        requireHash(normalizedItemId, "normalizedItemId");
        return transactions.executeRead(status -> repository.find(monitoringProfileId, normalizedItemId));
    }

    private static void requireHash(String value, String name) {
        requireNonBlank(value, name);
        if (value.length() != 64) {
            throw new IllegalArgumentException(name + " must contain 64 characters");
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
