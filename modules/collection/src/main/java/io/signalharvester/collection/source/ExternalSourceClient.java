package io.signalharvester.collection.source;

import io.signalharvester.configuration.api.ConfiguredSource;

/**
 * Loads raw content from one configured external source.
 *
 * <p>This interface is the collection module's boundary around external network I/O. Implementations
 * are responsible for transport concerns only; parsing and publication belong to later collection
 * stages.</p>
 */
public interface ExternalSourceClient {

    /**
     * Fetches the current raw representation of the configured source.
     *
     * @param source enabled or explicitly requested source configuration
     * @return fetched source content and transport metadata
     * @throws SourceFetchException when the source cannot be fetched within the configured contract
     */
    FetchedSourceContent fetch(ConfiguredSource source);
}
