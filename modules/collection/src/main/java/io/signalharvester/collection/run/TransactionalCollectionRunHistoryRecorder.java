package io.signalharvester.collection.run;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.collection.api.CollectionRunResult;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.util.Objects;

/** Owns the short write transaction used to persist one completed collection run. */
@Singleton
public final class TransactionalCollectionRunHistoryRecorder implements CollectionRunHistoryRecorder {
    private final CollectionRunHistoryStore store;
    private final TransactionOperations<Connection> transactions;

    public TransactionalCollectionRunHistoryRecorder(
            CollectionRunHistoryStore store,
            @Named("default") TransactionOperations<Connection> transactions) {
        this.store = Objects.requireNonNull(store, "store");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public void record(CollectionRunResult result) {
        Objects.requireNonNull(result, "result");
        transactions.executeWrite(status -> {
            store.save(result);
            return null;
        });
    }
}
