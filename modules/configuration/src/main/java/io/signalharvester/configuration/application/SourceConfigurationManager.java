package io.signalharvester.configuration.application;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceConfigurationProvider;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.persistence.MonitoringProfileRepository;
import io.signalharvester.configuration.persistence.SourceRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns configured-source CRUD use cases, transaction boundaries, and persisted configuration reads.
 */
@Singleton
public final class SourceConfigurationManager implements SourceConfigurationOperations, SourceConfigurationProvider {

    private final SourceRepository repository;
    private final MonitoringProfileRepository monitoringProfiles;
    private final TransactionOperations<Connection> transactions;
    private final Validator validator;

    public SourceConfigurationManager(
            SourceRepository repository,
            MonitoringProfileRepository monitoringProfiles,
            @Named("default") TransactionOperations<Connection> transactions,
            Validator validator) {
        this.repository = repository;
        this.monitoringProfiles = monitoringProfiles;
        this.transactions = transactions;
        this.validator = validator;
    }

    /**
     * Creates and persists a source with a new stable identifier in one transaction.
     *
     * @param command source configuration input
     * @return persisted source
     */
    @Override
    public ConfiguredSource create(SourceConfigurationCommand command) {
        validateCommand(command);
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
    @Override
    public List<ConfiguredSource> list() {
        return transactions.executeRead(status -> repository.findAll());
    }

    /**
     * Returns a configured source or fails when it does not exist.
     *
     * @param sourceId stable source identifier
     * @return configured source
     */
    @Override
    public ConfiguredSource get(SourceId sourceId) {
        return transactions.executeRead(status ->
                repository.findById(sourceId).orElseThrow(() -> new SourceNotFoundException(sourceId)));
    }

    /**
     * Replaces an existing source configuration atomically while preserving its stable identifier.
     *
     * @param sourceId stable source identifier
     * @param command replacement configuration
     * @return updated source
     */
    @Override
    public ConfiguredSource update(SourceId sourceId, SourceConfigurationCommand command) {
        validateCommand(command);
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
    @Override
    public void delete(SourceId sourceId) {
        transactions.executeWrite(status -> {
            if (monitoringProfiles.referencesSource(sourceId)) {
                throw new SourceInUseException(sourceId);
            }
            if (!repository.delete(sourceId)) {
                throw new SourceNotFoundException(sourceId);
            }
            return null;
        });
    }

    @Override
    public Optional<ConfiguredSource> findSource(SourceId sourceId) {
        return transactions.executeRead(status -> repository.findById(sourceId));
    }

    @Override
    public List<ConfiguredSource> findEnabledSources() {
        return transactions.executeRead(status -> repository.findEnabled());
    }

    private void validateCommand(SourceConfigurationCommand command) {
        Objects.requireNonNull(command, "command");
        var violations = validator.validate(command);
        if (!violations.isEmpty()) {
            ConstraintViolationException cause = new ConstraintViolationException(violations);
            throw new InvalidSourceConfigurationException(cause.getMessage(), cause);
        }
    }

    private static ConfiguredSource materialize(SourceId sourceId, SourceConfigurationCommand command) {
        try {
            return command.toConfiguredSource(sourceId);
        } catch (IllegalArgumentException exception) {
            throw new InvalidSourceConfigurationException(exception.getMessage(), exception);
        }
    }
}
