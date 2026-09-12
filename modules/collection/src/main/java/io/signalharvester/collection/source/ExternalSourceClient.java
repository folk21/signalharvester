package io.signalharvester.collection.source;

import io.signalharvester.configuration.api.ConfiguredSource;

/**
 * Loads raw content from configured external sources without exposing transport-specific APIs.
 *
 * <p>The contract is intentionally synchronous so collection workflows can remain imperative when
 * executed on Micronaut's blocking executor, which uses Virtual Threads on the Java 21 baseline.
 * Parsing and event publication remain separate collection responsibilities.</p>
 */
public interface ExternalSourceClient {

    /**
     * Fetches the current raw representation of a configured source.
     *
     * @param source enabled or explicitly requested source configuration
     * @return fetched source content and transport metadata
     * @throws SourceFetchException when the external request fails or returns a non-success status
     */
    FetchedSourceContent fetch(ConfiguredSource source);
}
