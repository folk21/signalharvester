package io.signalharvester.configuration.application;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.configuration.api.ConfiguredMonitoringProfile;
import io.signalharvester.configuration.api.MonitoringProfileConfigurationProvider;
import io.signalharvester.configuration.api.MonitoringProfileId;
import io.signalharvester.configuration.api.SourceConfigurationProvider;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.persistence.MonitoringProfileRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Owns monitoring-profile CRUD, validation, transactions, and effective profile reads. */
@Singleton
public final class MonitoringProfileConfigurationManager
        implements MonitoringProfileConfigurationOperations, MonitoringProfileConfigurationProvider {

    private final MonitoringProfileRepository repository;
    private final SourceConfigurationProvider sources;
    private final TransactionOperations<Connection> transactions;

    public MonitoringProfileConfigurationManager(
            MonitoringProfileRepository repository,
            SourceConfigurationProvider sources,
            @Named("default") TransactionOperations<Connection> transactions) {
        this.repository = repository;
        this.sources = sources;
        this.transactions = transactions;
    }

    @Override
    public ConfiguredMonitoringProfile create(MonitoringProfileConfigurationCommand command) {
        ConfiguredMonitoringProfile profile = materialize(MonitoringProfileId.of(UUID.randomUUID()), command);
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
        ConfiguredMonitoringProfile profile = materialize(profileId, command);
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
            MonitoringProfileConfigurationCommand command) {
        try {
            return command.toProfile(profileId);
        } catch (IllegalArgumentException exception) {
            throw new InvalidMonitoringProfileConfigurationException(exception.getMessage(), exception);
        }
    }
}
