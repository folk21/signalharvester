package io.signalharvester.collection.run;

import jakarta.inject.Singleton;
import java.util.List;
import java.util.Objects;

/** Read application service for bounded operational inspection of completed collection runs. */
@Singleton
public final class CollectionRunHistoryQuery {
    private final CollectionRunHistoryStore store;

    public CollectionRunHistoryQuery(CollectionRunHistoryStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public List<CollectionRunResult> recent(int limit) {
        return store.findRecent(limit);
    }

    public CollectionRunResult get(String collectionRunId) {
        return store.findById(collectionRunId)
                .orElseThrow(() -> new CollectionRunNotFoundException(collectionRunId));
    }
}
