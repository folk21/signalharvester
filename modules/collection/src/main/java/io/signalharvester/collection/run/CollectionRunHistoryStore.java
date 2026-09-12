package io.signalharvester.collection.run;

import io.signalharvester.collection.api.CollectionRunResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores and queries durable operational snapshots of completed collection runs.
 * Every operation requires an application-owned active JDBC transaction.
 */
public interface CollectionRunHistoryStore {
    void save(CollectionRunResult result);
    List<CollectionRunResult> findRecent(int limit);
    Optional<CollectionRunResult> findById(UUID collectionRunId);
}
