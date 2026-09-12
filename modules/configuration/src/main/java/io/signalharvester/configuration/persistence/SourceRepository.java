package io.signalharvester.configuration.persistence;

import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceId;
import java.util.List;
import java.util.Optional;

/**
 * Defines the configuration module's internal persistence boundary for configured sources.
 */
public interface SourceRepository {

    /**
     * Returns all persisted sources in deterministic repository order.
     */
    List<ConfiguredSource> findAll();

    /**
     * Returns only enabled persisted sources in deterministic repository order.
     */
    List<ConfiguredSource> findEnabled();

    /**
     * Finds a persisted source by its stable identifier.
     */
    Optional<ConfiguredSource> findById(SourceId sourceId);

    /**
     * Persists a new source and its settings within the caller-owned write transaction.
     */
    void insert(ConfiguredSource source);

    /**
     * Replaces an existing source and its settings within the caller-owned write transaction.
     *
     * @return {@code true} when the source existed and was updated
     */
    boolean update(ConfiguredSource source);

    /**
     * Deletes a source by identifier within the caller-owned write transaction.
     *
     * @return {@code true} when the source existed and was deleted
     */
    boolean delete(SourceId sourceId);
}
