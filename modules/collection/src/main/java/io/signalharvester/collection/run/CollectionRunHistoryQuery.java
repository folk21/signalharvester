package io.signalharvester.collection.run;

import io.micronaut.transaction.TransactionOperations;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read application service for bounded operational inspection of completed collection runs. */
@Singleton
public final class CollectionRunHistoryQuery implements CollectionRunHistory {
    private final CollectionRunHistoryStore store;
    private final TransactionOperations<Connection> transactions;

    public CollectionRunHistoryQuery(
            CollectionRunHistoryStore store,
            @Named("default") TransactionOperations<Connection> transactions) {
        this.store = Objects.requireNonNull(store, "store");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public List<CollectionRunResult> recent(int limit) {
        requireRecentLimit(limit);
        return transactions.executeRead(status -> store.findRecent(limit));
    }

    @Override
    public CollectionRunResult get(UUID collectionRunId) {
        Objects.requireNonNull(collectionRunId, "collectionRunId");
        return transactions.executeRead(status -> store.findById(collectionRunId)
                .orElseThrow(() -> new CollectionRunNotFoundException(collectionRunId)));
    }

    private static void requireRecentLimit(int limit) {
        if (limit < MIN_RECENT_LIMIT || limit > MAX_RECENT_LIMIT) {
            throw new IllegalArgumentException(
                    "limit must be between " + MIN_RECENT_LIMIT + " and " + MAX_RECENT_LIMIT + ": " + limit);
        }
    }
}
