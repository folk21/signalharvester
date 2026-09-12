package io.signalharvester.collection.api;

import java.util.List;

/** Read-only application API for bounded operational inspection of completed collection runs. */
public interface CollectionRunHistory {

    /** Returns the most recent completed runs, newest first. */
    List<CollectionRunResult> recent(int limit);

    /** Returns one completed run or fails when it does not exist. */
    CollectionRunResult get(String collectionRunId);
}
