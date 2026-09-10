package io.signalharvester.configuration.application;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceConfigurationProvider;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.persistence.SourceRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns configured-source CRUD use cases, transaction boundaries, and persisted configuration reads.
 */
@Singleton
public final class SourceConfigurationManager implements SourceConfigurationProvider {

    private final SourceRepository repository;
    private final TransactionOperations<Connection> transactions;

    public SourceConfigurationManager(
            SourceRepository repository,
            @Named("default") TransactionOperations<Connection> transactions) {
        this.repository = repository;
        this.transactions = transactions;
    }

    /**
     * Creates and persists a source with a new stable identifier in one transaction.
     *
     * @param command source configuration input
     * @return persisted source
     */
    public ConfiguredSource create(SourceConfigurationCommand command) {
        SourceId sourceId = SourceId.of(UUID.randomUUID());
        ConfiguredSource source = materialize(sourceId, command);
        return transactions.executeWrite(status -> {
            repository.insert(source);
            return source;
        });
    }

    /**
     * Returns all configured sources in deterministic display order.
     *
     * @return configured sources
     */
    public List<ConfiguredSource> list() {
        return repository.findAll();
    }

    /**
     * Returns a configured source or fails when it does not exist.
     *
     * @param sourceId stable source identifier
     * @return configured source
     */
    public ConfiguredSource get(SourceId sourceId) {
        return repository.findById(sourceId).orElseThrow(() -> new SourceNotFoundException(sourceId));
    }

    /**
     * Replaces an existing source configuration atomically while preserving its stable identifier.
     *
     * @param sourceId stable source identifier
     * @param command replacement configuration
     * @return updated source
     */
    public ConfiguredSource update(SourceId sourceId, SourceConfigurationCommand command) {
        ConfiguredSource source = materialize(sourceId, command);
        return transactions.executeWrite(status -> {
            if (!repository.update(source)) {
                throw new SourceNotFoundException(sourceId);
            }
            return source;
        });
    }

    /**
     * Deletes a configured source in a transaction.
     *
     * @param sourceId stable source identifier
     */
    public void delete(SourceId sourceId) {
        transactions.executeWrite(status -> {
            if (!repository.delete(sourceId)) {
                throw new SourceNotFoundException(sourceId);
            }
            return null;
        });
    }

    @Override
    public Optional<ConfiguredSource> findSource(SourceId sourceId) {
        return repository.findById(sourceId);
    }

    @Override
    public List<ConfiguredSource> findEnabledSources() {
        return repository.findEnabled();
    }

    private static ConfiguredSource materialize(SourceId sourceId, SourceConfigurationCommand command) {
        try {
            return command.toConfiguredSource(sourceId);
        } catch (IllegalArgumentException exception) {
            throw new InvalidSourceConfigurationException(exception.getMessage(), exception);
        }
    }
}
