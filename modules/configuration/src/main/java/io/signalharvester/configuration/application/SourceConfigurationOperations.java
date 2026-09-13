package io.signalharvester.configuration.application;

import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceId;
import java.util.List;

/**
 * Internal application boundary for administering configured external sources.
 *
 * <p>Configuration-owned inbound adapters depend on this contract instead of the concrete application
 * implementation. It is not a published cross-module API; other functional modules consume the narrower
 * {@code configuration.api} contracts only.</p>
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
