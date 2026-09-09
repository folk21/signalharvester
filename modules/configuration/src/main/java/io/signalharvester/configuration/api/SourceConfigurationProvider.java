package io.signalharvester.configuration.api;

import java.util.List;
import java.util.Optional;

/**
 * Exposes effective source configuration to modules that need synchronous configuration access.
 */
public interface SourceConfigurationProvider {

    /**
     * Finds a source by its stable identifier.
     *
     * @param sourceId source identifier
     * @return configured source when it exists
     */
    Optional<ConfiguredSource> findSource(SourceId sourceId);

    /**
     * Returns sources that are currently enabled for collection.
     *
     * @return immutable or read-only view of enabled sources
     */
    List<ConfiguredSource> findEnabledSources();
}
