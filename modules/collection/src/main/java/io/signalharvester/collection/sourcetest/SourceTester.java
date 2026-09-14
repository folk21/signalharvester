package io.signalharvester.collection.sourcetest;

import io.signalharvester.configuration.api.SourceId;

/** Internal application boundary for diagnostic source fetch and extraction. */
public interface SourceTester {

    /** Tests one persisted source without publishing events or collection-run history. */
    SourceTestResult test(SourceId sourceId);
}
