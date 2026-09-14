package io.signalharvester.results.application;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.results.persistence.ResultLiveQueryRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;

/** Owns short read-only transactions used by resumable live-result polling. */
@Singleton
public final class ResultLiveQueryService implements ResultLiveQuery {

    public static final int MAX_BATCH_SIZE = 200;

    private final ResultLiveQueryRepository repository;
    private final TransactionOperations<Connection> transactions;

    public ResultLiveQueryService(
            ResultLiveQueryRepository repository,
            @Named("default") TransactionOperations<Connection> transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public long currentCursor() {
        return transactions.executeRead(status -> repository.currentCursor());
    }

    @Override
    public ResultLiveBatch pollAfter(long cursor, ResultLiveCriteria criteria, int maxItems) {
        if (cursor < 0) {
            throw new IllegalArgumentException("cursor must not be negative");
        }
        Objects.requireNonNull(criteria, "criteria");
        if (maxItems < 1 || maxItems > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("maxItems must be between 1 and " + MAX_BATCH_SIZE);
        }

        return transactions.executeRead(status -> {
            long highWatermark = repository.currentCursor();
            if (highWatermark < cursor) {
                return new ResultLiveBatch(highWatermark, List.of());
            }
            if (highWatermark == cursor) {
                return new ResultLiveBatch(cursor, List.of());
            }

            List<ResultLiveUpdate> updates = repository.findUpdatesAfter(
                    cursor, highWatermark, criteria, maxItems);
            long nextCursor = updates.size() == maxItems
                    ? updates.get(updates.size() - 1).eventId()
                    : highWatermark;
            return new ResultLiveBatch(nextCursor, updates);
        });
    }
}
