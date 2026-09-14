package io.signalharvester.results.persistence;

import io.signalharvester.results.application.ResultLiveCriteria;
import io.signalharvester.results.application.ResultLiveUpdate;
import java.util.List;

/** Persistence boundary for durable live-result cursors and current analyzed-result projections. */
public interface ResultLiveQueryRepository {

    /** Returns the highest cursor currently represented by committed live-result state. */
    long currentCursor();

    /** Returns matching current projections in cursor order within the supplied closed high-watermark range. */
    List<ResultLiveUpdate> findUpdatesAfter(long cursor, long throughCursor, ResultLiveCriteria criteria, int limit);
}
