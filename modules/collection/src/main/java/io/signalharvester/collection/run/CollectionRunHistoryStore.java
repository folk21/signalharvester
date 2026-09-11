package io.signalharvester.collection.run;

import java.util.List;
import java.util.Optional;

/** Stores and queries durable operational snapshots of completed collection runs. */
public interface CollectionRunHistoryStore {
    void save(CollectionRunResult result);
    List<CollectionRunResult> findRecent(int limit);
    Optional<CollectionRunResult> findById(String collectionRunId);
}
