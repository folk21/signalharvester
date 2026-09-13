package io.signalharvester.collection.run;

import java.util.List;
import java.util.UUID;

/** Read-only application API for bounded operational inspection of completed collection runs. */
public interface CollectionRunHistory {
    int MIN_RECENT_LIMIT = 1;
    int MAX_RECENT_LIMIT = 200;

    /**
     * Returns the most recent completed runs, newest first.
     *
     * @throws IllegalArgumentException when {@code limit} is outside the supported range
     */
    List<CollectionRunResult> recent(int limit);

    /** Returns one completed run or fails when it does not exist. */
    CollectionRunResult get(UUID collectionRunId);
}
