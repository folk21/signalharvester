package io.signalharvester.configuration.api;

import java.util.List;

/**
 * Primary synchronous application API for administering configured external sources.
 *
 * <p>The HTTP adapter uses this contract instead of depending on the concrete application implementation.
 * Other modules should prefer the narrower {@link SourceConfigurationProvider} unless they intentionally
 * own a use case that administers configuration.</p>
 */
public interface SourceConfigurationOperations {

    /** Creates and persists a source with a new stable identifier. */
    ConfiguredSource create(SourceConfigurationCommand command);

    /** Returns all configured sources in deterministic display order. */
    List<ConfiguredSource> list();

    /** Returns one configured source or fails when it does not exist. */
    ConfiguredSource get(SourceId sourceId);

    /** Replaces an existing source configuration while preserving its stable identifier. */
    ConfiguredSource update(SourceId sourceId, SourceConfigurationCommand command);

    /** Deletes an existing configured source. */
    void delete(SourceId sourceId);
}
