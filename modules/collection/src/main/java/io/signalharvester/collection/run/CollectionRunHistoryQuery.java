package io.signalharvester.collection.run;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.collection.api.CollectionRunHistory;
import io.signalharvester.collection.api.CollectionRunResult;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;

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
        return transactions.executeRead(status -> store.findRecent(limit));
    }

    @Override
    public CollectionRunResult get(String collectionRunId) {
        return transactions.executeRead(status -> store.findById(collectionRunId)
                .orElseThrow(() -> new CollectionRunNotFoundException(collectionRunId)));
    }
}
