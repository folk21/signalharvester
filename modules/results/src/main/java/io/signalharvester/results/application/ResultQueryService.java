package io.signalharvester.results.application;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.results.persistence.ResultQueryRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Owns short read-only JDBC transactions for Results browsing, pagination, search, and detail queries. */
@Singleton
public final class ResultQueryService implements ResultQuery {

    private final ResultQueryRepository repository;
    private final ResultCursorCodec cursorCodec;
    private final TransactionOperations<Connection> transactions;

    public ResultQueryService(
            ResultQueryRepository repository,
            ResultCursorCodec cursorCodec,
            @Named("default") TransactionOperations<Connection> transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursorCodec");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public ResultPage browse(ResultQueryCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria");
        Optional<ResultPagePosition> after = criteria.cursor().map(cursor -> cursorCodec.decode(cursor, criteria));
        int fetchLimit = criteria.limit() + 1;
        List<ResultSummary> fetched = transactions.executeRead(status -> repository.findPage(criteria, after, fetchLimit));
        boolean hasMore = fetched.size() > criteria.limit();
        List<ResultSummary> page = hasMore ? List.copyOf(fetched.subList(0, criteria.limit())) : List.copyOf(fetched);
        Optional<String> nextCursor = hasMore
                ? Optional.of(cursorCodec.encode(page.getLast(), criteria))
                : Optional.empty();
        return new ResultPage(page, nextCursor);
    }

    @Override
    public Optional<ResultDetail> find(String monitoringProfileId, String normalizedItemId) {
        requireNonBlank(monitoringProfileId, "monitoringProfileId");
        requireHash(normalizedItemId, "normalizedItemId");
        return transactions.executeRead(status -> repository.find(monitoringProfileId, normalizedItemId));
    }

    private static void requireHash(String value, String name) {
        requireNonBlank(value, name);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new InvalidResultQueryException(name + " must contain 64 lowercase hexadecimal characters");
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new InvalidResultQueryException(name + " must not be blank");
        }
    }
}
