package io.signalharvester.configuration.application;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.configuration.api.ConfiguredMonitoringProfile;
import io.signalharvester.configuration.api.MonitoringProfileAnalysisSettings;
import io.signalharvester.configuration.api.MonitoringProfileConfigurationProvider;
import io.signalharvester.configuration.api.MonitoringProfileId;
import io.signalharvester.configuration.api.SourceConfigurationProvider;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.persistence.MonitoringProfileRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Owns monitoring-profile CRUD, validation, transactions, and effective profile reads. */
@Singleton
public final class MonitoringProfileConfigurationManager
        implements MonitoringProfileConfigurationOperations, MonitoringProfileConfigurationProvider {

    private final MonitoringProfileRepository repository;
    private final SourceConfigurationProvider sources;
    private final TransactionOperations<Connection> transactions;
    private final Validator validator;

    public MonitoringProfileConfigurationManager(
            MonitoringProfileRepository repository,
            SourceConfigurationProvider sources,
            @Named("default") TransactionOperations<Connection> transactions,
            Validator validator) {
        this.repository = repository;
        this.sources = sources;
        this.transactions = transactions;
        this.validator = validator;
    }

    @Override
    public ConfiguredMonitoringProfile create(MonitoringProfileConfigurationCommand command) {
        validateCommand(command);
        MonitoringProfileAnalysisSettings effectiveSettings = command.analysisSettings()
                .orElseGet(MonitoringProfileAnalysisSettings::allRelevant);
        ConfiguredMonitoringProfile profile = materialize(
                MonitoringProfileId.of(UUID.randomUUID()), command, effectiveSettings);
        validateSources(profile);
        return transactions.executeWrite(status -> {
            repository.insert(profile);
            return profile;
        });
    }

    @Override
    public List<ConfiguredMonitoringProfile> list() {
        return transactions.executeRead(status -> repository.findAll());
    }

    @Override
    public ConfiguredMonitoringProfile get(MonitoringProfileId profileId) {
        return transactions.executeRead(status -> repository.findById(profileId)
                .orElseThrow(() -> new MonitoringProfileNotFoundException(profileId)));
    }

    @Override
    public ConfiguredMonitoringProfile update(
            MonitoringProfileId profileId,
            MonitoringProfileConfigurationCommand command) {
        validateCommand(command);
        ConfiguredMonitoringProfile current = get(profileId);
        MonitoringProfileAnalysisSettings effectiveSettings = command.analysisSettings().orElse(current.analysisSettings());
        ConfiguredMonitoringProfile profile = materialize(profileId, command, effectiveSettings);
        validateSources(profile);
        return transactions.executeWrite(status -> {
            if (!repository.update(profile)) {
                throw new MonitoringProfileNotFoundException(profileId);
            }
            return profile;
        });
    }

    @Override
    public void delete(MonitoringProfileId profileId) {
        transactions.executeWrite(status -> {
            if (!repository.delete(profileId)) {
                throw new MonitoringProfileNotFoundException(profileId);
            }
            return null;
        });
    }

    @Override
    public Optional<ConfiguredMonitoringProfile> findProfile(MonitoringProfileId profileId) {
        return transactions.executeRead(status -> repository.findById(profileId));
    }

    @Override
    public List<ConfiguredMonitoringProfile> findEnabledProfiles() {
        return transactions.executeRead(status -> repository.findEnabled());
    }

    private void validateCommand(MonitoringProfileConfigurationCommand command) {
        Objects.requireNonNull(command, "command");
        var violations = validator.validate(command);
        if (!violations.isEmpty()) {
            ConstraintViolationException cause = new ConstraintViolationException(violations);
            throw new InvalidMonitoringProfileConfigurationException(cause.getMessage(), cause);
        }
    }

    private void validateSources(ConfiguredMonitoringProfile profile) {
        List<SourceId> missing = profile.sourceIds().stream()
                .filter(sourceId -> sources.findSource(sourceId).isEmpty())
                .toList();
        if (!missing.isEmpty()) {
            throw new InvalidMonitoringProfileConfigurationException(
                    "Unknown source ids: " + missing.stream().map(id -> id.value().toString()).toList());
        }
    }

    private static ConfiguredMonitoringProfile materialize(
            MonitoringProfileId profileId,
            MonitoringProfileConfigurationCommand command,
            MonitoringProfileAnalysisSettings analysisSettings) {
        try {
            return command.toProfile(profileId, analysisSettings);
        } catch (IllegalArgumentException exception) {
            throw new InvalidMonitoringProfileConfigurationException(exception.getMessage(), exception);
        }
    }
}
