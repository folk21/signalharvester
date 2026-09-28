package io.signalharvester.configuration.application;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceConfigurationProvider;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.persistence.MonitoringProfileRepository;
import io.signalharvester.configuration.persistence.SourceRepository;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns configured-source CRUD use cases, transaction boundaries, and persisted configuration reads. */
@Singleton
public final class SourceConfigurationManager implements SourceConfigurationOperations, SourceConfigurationProvider {
    private static final Logger LOG = LoggerFactory.getLogger(SourceConfigurationManager.class);

    private final SourceRepository repository;
    private final MonitoringProfileRepository monitoringProfiles;
    private final TransactionOperations<Connection> transactions;
    private final Validator validator;
    private final OperationalChangeJournal changeJournal;

    public SourceConfigurationManager(
            SourceRepository repository,
            MonitoringProfileRepository monitoringProfiles,
            @Named("default") TransactionOperations<Connection> transactions,
            Validator validator,
            OperationalChangeJournal changeJournal) {
        this.repository = repository;
        this.monitoringProfiles = monitoringProfiles;
        this.transactions = transactions;
        this.validator = validator;
        this.changeJournal = changeJournal;
    }

    @Override
    public ConfiguredSource create(SourceConfigurationCommand command, OperationalChangeContext changeContext) {
        try {
            validateCommand(command);
            SourceId sourceId = SourceId.of(UUID.randomUUID());
            ConfiguredSource source = materialize(sourceId, command);
            return transactions.executeWrite(status -> {
                repository.insert(source);
                recordApplied(changeContext, sourceId.value().toString(), Map.of(), summarize(source));
                return source;
            });
        } catch (OperationalChangeRecordingException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            recordRejected(changeContext, "new", Map.of(), summarize(command));
            throw failure;
        }
    }

    @Override
    public List<ConfiguredSource> list() {
        return transactions.executeRead(status -> repository.findAll());
    }

    @Override
    public ConfiguredSource get(SourceId sourceId) {
        return transactions.executeRead(status ->
                repository.findById(sourceId).orElseThrow(() -> new SourceNotFoundException(sourceId)));
    }

    @Override
    public ConfiguredSource update(
            SourceId sourceId,
            SourceConfigurationCommand command,
            OperationalChangeContext changeContext) {
        try {
            validateCommand(command);
            ConfiguredSource replacement = materialize(sourceId, command);
            return transactions.executeWrite(status -> {
                ConfiguredSource current = repository.findById(sourceId)
                        .orElseThrow(() -> new SourceNotFoundException(sourceId));
                if (!repository.update(replacement)) {
                    throw new SourceNotFoundException(sourceId);
                }
                recordApplied(
                        changeContext,
                        sourceId.value().toString(),
                        summarize(current),
                        summarize(replacement));
                return replacement;
            });
        } catch (OperationalChangeRecordingException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            recordRejected(changeContext, sourceId.value().toString(), Map.of(), summarize(command));
            throw failure;
        }
    }

    @Override
    public void delete(SourceId sourceId, OperationalChangeContext changeContext) {
        try {
            transactions.executeWrite(status -> {
                ConfiguredSource current = repository.findById(sourceId)
                        .orElseThrow(() -> new SourceNotFoundException(sourceId));
                if (monitoringProfiles.referencesSource(sourceId)) {
                    throw new SourceInUseException(sourceId);
                }
                if (!repository.delete(sourceId)) {
                    throw new SourceNotFoundException(sourceId);
                }
                recordApplied(changeContext, sourceId.value().toString(), summarize(current), Map.of());
                return null;
            });
        } catch (OperationalChangeRecordingException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            recordRejected(changeContext, sourceId.value().toString(), Map.of(), Map.of("operation", "delete"));
            throw failure;
        }
    }

    @Override
    public Optional<ConfiguredSource> findSource(SourceId sourceId) {
        return transactions.executeRead(status -> repository.findById(sourceId));
    }

    @Override
    public List<ConfiguredSource> findEnabledSources() {
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
                OperationalChangeCategory.SOURCE_CONFIGURATION,
                OperationalChangeTargetType.SOURCE,
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
                    OperationalChangeCategory.SOURCE_CONFIGURATION,
                    OperationalChangeTargetType.SOURCE,
                    targetId,
                    before,
                    after,
                    OperationalChangeOutcome.REJECTED,
                    context));
        } catch (RuntimeException journalFailure) {
            LOG.warn("Failed to journal rejected Source mutation targetId={}", targetId, journalFailure);
        }
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

    private static Map<String, String> summarize(ConfiguredSource source) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", source.name());
        values.put("type", source.type().name());
        values.put("location", sanitizedLocation(source.location()));
        values.put("enabled", Boolean.toString(source.enabled()));
        values.put("settingKeys", source.settings().keySet().stream().sorted().reduce((a, b) -> a + "," + b).orElse(""));
        return Map.copyOf(values);
    }

    private static Map<String, String> summarize(SourceConfigurationCommand command) {
        if (command == null) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", command.name() == null ? "" : command.name());
        values.put("type", command.type() == null ? "" : command.type().name());
        values.put("location", command.location() == null ? "" : sanitizedLocation(command.location()));
        values.put("enabled", Boolean.toString(command.enabled()));
        values.put("settingKeys", command.settings() == null
                ? ""
                : command.settings().keySet().stream().sorted(Comparator.naturalOrder())
                        .reduce((a, b) -> a + "," + b).orElse(""));
        return Map.copyOf(values);
    }

    private static String sanitizedLocation(java.net.URI location) {
        StringBuilder value = new StringBuilder();
        value.append(location.getScheme()).append("://").append(location.getHost());
        if (location.getPort() >= 0) {
            value.append(':').append(location.getPort());
        }
        if (location.getPath() != null) {
            value.append(location.getPath());
        }
        return value.toString();
    }
}
