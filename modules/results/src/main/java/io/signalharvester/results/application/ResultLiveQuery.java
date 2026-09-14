package io.signalharvester.results.application;

/** Internal application boundary for resumable live analyzed-result delivery. */
public interface ResultLiveQuery {

    /** Returns the current global live-result cursor. */
    long currentCursor();

    /** Returns a bounded batch after the supplied cursor and advances across the inspected range. */
    ResultLiveBatch pollAfter(long cursor, ResultLiveCriteria criteria, int maxItems);
}
