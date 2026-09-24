package io.signalharvester.configuration.application;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.configuration.api.ConfiguredMonitoringProfile;
import io.signalharvester.configuration.api.MonitoringProfileAnalysisSettings;
import io.signalharvester.configuration.api.MonitoringProfileConfigurationProvider;
import io.signalharvester.configuration.api.MonitoringProfileId;
import io.signalharvester.configuration.api.SourceConfigurationProvider;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.persistence.MonitoringProfileRepository;
import io.signalharvester.operations.api.OperationalChangeCategory;
import io.signalharvester.operations.api.OperationalChangeContext;
import io.signalharvester.operations.api.OperationalChangeJournal;
import io.signalharvester.operations.api.OperationalChangeOutcome;
import io.signalharvester.operations.api.OperationalChangeRecordingException;
import io.signalharvester.operations.api.OperationalChangeRequest;
import io.signalharvester.operations.api.OperationalChangeTargetType;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns monitoring-profile CRUD, validation, transactions, effective profile reads, and change journaling. */
@Singleton
public final class MonitoringProfileConfigurationManager
        implements MonitoringProfileConfigurationOperations, MonitoringProfileConfigurationProvider {
    private static final Logger LOG = LoggerFactory.getLogger(MonitoringProfileConfigurationManager.class);

    private final MonitoringProfileRepository repository;
    private final SourceConfigurationProvider sources;
    private final TransactionOperations<Connection> transactions;
    private final Validator validator;
    private final OperationalChangeJournal changeJournal;

    public MonitoringProfileConfigurationManager(
            MonitoringProfileRepository repository,
            SourceConfigurationProvider sources,
            @Named("default") TransactionOperations<Connection> transactions,
            Validator validator,
            OperationalChangeJournal changeJournal) {
        this.repository = repository;
        this.sources = sources;
        this.transactions = transactions;
        this.validator = validator;
        this.changeJournal = changeJournal;
    }

    @Override
    public ConfiguredMonitoringProfile create(
            MonitoringProfileConfigurationCommand command,
            OperationalChangeContext changeContext) {
        try {
            validateCommand(command);
            MonitoringProfileAnalysisSettings effectiveSettings = command.analysisSettings()
                    .orElseGet(MonitoringProfileAnalysisSettings::allRelevant);
            ConfiguredMonitoringProfile profile = materialize(
                    MonitoringProfileId.of(UUID.randomUUID()), command, effectiveSettings);
            validateSources(profile);
            return transactions.executeWrite(status -> {
                repository.insert(profile);
                recordApplied(changeContext, profile.id().value().toString(), Map.of(), summarize(profile));
                return profile;
            });
        } catch (OperationalChangeRecordingException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            recordRejected(changeContext, "new", Map.of(), summarize(command));
            throw failure;
        }
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
            MonitoringProfileConfigurationCommand command,
            OperationalChangeContext changeContext) {
        try {
            validateCommand(command);
            ConfiguredMonitoringProfile current = get(profileId);
            MonitoringProfileAnalysisSettings effectiveSettings = command.analysisSettings().orElse(current.analysisSettings());
            ConfiguredMonitoringProfile replacement = materialize(profileId, command, effectiveSettings);
            validateSources(replacement);
            return transactions.executeWrite(status -> {
                ConfiguredMonitoringProfile transactionalCurrent = repository.findById(profileId)
                        .orElseThrow(() -> new MonitoringProfileNotFoundException(profileId));
                if (!repository.update(replacement)) {
                    throw new MonitoringProfileNotFoundException(profileId);
                }
                recordApplied(
                        changeContext,
                        profileId.value().toString(),
                        summarize(transactionalCurrent),
                        summarize(replacement));
                return replacement;
            });
        } catch (OperationalChangeRecordingException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            recordRejected(changeContext, profileId.value().toString(), Map.of(), summarize(command));
            throw failure;
        }
    }

    @Override
    public void delete(MonitoringProfileId profileId, OperationalChangeContext changeContext) {
        try {
            transactions.executeWrite(status -> {
                ConfiguredMonitoringProfile current = repository.findById(profileId)
                        .orElseThrow(() -> new MonitoringProfileNotFoundException(profileId));
                if (!repository.delete(profileId)) {
                    throw new MonitoringProfileNotFoundException(profileId);
                }
                recordApplied(changeContext, profileId.value().toString(), summarize(current), Map.of());
                return null;
            });
        } catch (OperationalChangeRecordingException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            recordRejected(changeContext, profileId.value().toString(), Map.of(), Map.of("operation", "delete"));
            throw failure;
        }
    }

    @Override
    public Optional<ConfiguredMonitoringProfile> findProfile(MonitoringProfileId profileId) {
        return transactions.executeRead(status -> repository.findById(profileId));
    }

    @Override
    public List<ConfiguredMonitoringProfile> findEnabledProfiles() {
        return transactions.executeRead(status -> repository.findEnabled());
    }

    private void recordApplied(
            OperationalChangeContext context,
            String targetId,
            Map<String, String> before,
            Map<String, String> after) {
        if (!context.journalEnabled()) {
            return;
        }
        changeJournal.recordInCurrentTransaction(new OperationalChangeRequest(
                OperationalChangeCategory.MONITORING_PROFILE_CONFIGURATION,
                OperationalChangeTargetType.MONITORING_PROFILE,
                targetId,
                before,
                after,
                OperationalChangeOutcome.APPLIED,
                context));
    }

    private void recordRejected(
            OperationalChangeContext context,
            String targetId,
            Map<String, String> before,
            Map<String, String> after) {
        if (!context.journalEnabled()) {
            return;
        }
        try {
            changeJournal.record(new OperationalChangeRequest(
                    OperationalChangeCategory.MONITORING_PROFILE_CONFIGURATION,
                    OperationalChangeTargetType.MONITORING_PROFILE,
                    targetId,
                    before,
                    after,
                    OperationalChangeOutcome.REJECTED,
                    context));
        } catch (RuntimeException journalFailure) {
            LOG.warn("Failed to journal rejected Monitoring Profile mutation targetId={}", targetId, journalFailure);
        }
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
            throw new InvalidMonitoringProfileConfigurationException("Unknown source ids: " + missing);
        }
    }

    private static ConfiguredMonitoringProfile materialize(
            MonitoringProfileId profileId,
            MonitoringProfileConfigurationCommand command,
            MonitoringProfileAnalysisSettings effectiveSettings) {
        try {
            return command.toProfile(profileId, effectiveSettings);
        } catch (IllegalArgumentException exception) {
            throw new InvalidMonitoringProfileConfigurationException(exception.getMessage(), exception);
        }
    }

    private static Map<String, String> summarize(ConfiguredMonitoringProfile profile) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", profile.name());
        values.put("informationCategory", profile.informationCategory());
        values.put("enabled", Boolean.toString(profile.enabled()));
        values.put("collectionIntervalMinutes", Integer.toString(profile.collectionIntervalMinutes()));
        values.put("sourceIds", profile.sourceIds().stream().map(id -> id.value().toString()).reduce((a, b) -> a + "," + b).orElse(""));
        values.put("criteriaKeys", profile.criteria().keySet().stream().sorted().reduce((a, b) -> a + "," + b).orElse(""));
        values.put("analysisMode", profile.analysisSettings().keywords().isEmpty() ? "ALL_RELEVANT" : "KEYWORD_FILTER");
        values.put("analysisKeywordCount", Integer.toString(profile.analysisSettings().keywords().size()));
        values.put("analysisMinimumMatches", Integer.toString(profile.analysisSettings().minimumMatches()));
        return Map.copyOf(values);
    }

    private static Map<String, String> summarize(MonitoringProfileConfigurationCommand command) {
        if (command == null) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", command.name() == null ? "" : command.name());
        values.put("informationCategory", command.informationCategory() == null ? "" : command.informationCategory());
        values.put("enabled", Boolean.toString(command.enabled()));
        values.put("collectionIntervalMinutes", Integer.toString(command.collectionIntervalMinutes()));
        values.put("sourceIds", command.sourceIds() == null
                ? ""
                : command.sourceIds().stream().filter(Objects::nonNull).map(id -> id.value().toString())
                        .reduce((a, b) -> a + "," + b).orElse(""));
        values.put("criteriaKeys", command.criteria() == null
                ? ""
                : command.criteria().keySet().stream().filter(Objects::nonNull).sorted()
                        .reduce((a, b) -> a + "," + b).orElse(""));
        MonitoringProfileAnalysisSettings settings = command.analysisSettings() == null
                ? null
                : command.analysisSettings().orElse(null);
        values.put("analysisMode", settings == null
                ? "PRESERVE_OR_DEFAULT"
                : (settings.keywords().isEmpty() ? "ALL_RELEVANT" : "KEYWORD_FILTER"));
        values.put("analysisKeywordCount", settings == null ? "" : Integer.toString(settings.keywords().size()));
        values.put("analysisMinimumMatches", settings == null ? "" : Integer.toString(settings.minimumMatches()));
        return Map.copyOf(values);
    }
}
