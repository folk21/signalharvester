package io.signalharvester.operations.assisted;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.operations.model.HealthSnapshot;
import io.signalharvester.operations.persistence.OperationalIntelligenceRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Persists eligible automatic-investigation triggers from immutable Health Snapshot state. */
@Singleton
public final class AutomaticInvestigationPlanner {
    private final OperationalIntelligenceRepository repository;
    private final TransactionOperations<Connection> transactions;
    private final AssistedInvestigationConfiguration configuration;
    private final AutomaticInvestigationPolicy policy;
    private final Clock clock;
    private final AutomaticInvestigationMode mode;

    public AutomaticInvestigationPlanner(
            OperationalIntelligenceRepository repository,
            @Named("default") TransactionOperations<Connection> transactions,
            AssistedInvestigationConfiguration configuration,
            AutomaticInvestigationPolicy policy) {
        this(repository, transactions, configuration, policy, Clock.systemUTC());
    }

    AutomaticInvestigationPlanner(
            OperationalIntelligenceRepository repository,
            TransactionOperations<Connection> transactions,
            AssistedInvestigationConfiguration configuration,
            AutomaticInvestigationPolicy policy,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mode = AutomaticInvestigationMode.parse(configuration.getAutomaticMode());
    }

    /** Plans against the latest persisted snapshot inside a new short transaction. */
    public Optional<AutomaticInvestigationTrigger> planLatest() {
        if (!enabled()) {
            return Optional.empty();
        }
        return transactions.executeWrite(status -> repository.findLatestSnapshot()
                .flatMap(snapshot -> planInCurrentTransaction(snapshot, clock.instant())));
    }

    /** Plans against one just-persisted snapshot inside the caller-owned transaction. */
    public Optional<AutomaticInvestigationTrigger> planInCurrentTransaction(HealthSnapshot snapshot, Instant now) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(now, "now");
        if (!enabled() || !repository.tryAcquireAutomaticInvestigationPlanningLock()) {
            return Optional.empty();
        }
        HealthSnapshot previous = repository.findRecentSnapshotsBefore(snapshot.generatedAt(), 1)
                .stream().findFirst().orElse(null);
        AutomaticInvestigationTrigger latestTrigger = repository.findLatestAutomaticInvestigationTrigger()
                .orElse(null);
        HealthSnapshot latestTriggerSnapshot = latestTrigger == null
                ? null
                : repository.findSnapshot(latestTrigger.snapshotId()).orElse(null);
        return policy.evaluate(
                        mode,
                        snapshot,
                        previous,
                        latestTrigger,
                        latestTriggerSnapshot,
                        now,
                        configuration.getAutomaticCooldown(),
                        configuration.getPeriodicReassessmentInterval())
                .flatMap(type -> insert(snapshot.id(), type, now));
    }

    private Optional<AutomaticInvestigationTrigger> insert(
            UUID snapshotId, AutomaticInvestigationTriggerType type, Instant now) {
        AutomaticInvestigationTrigger trigger = new AutomaticInvestigationTrigger(
                UUID.randomUUID(),
                snapshotId,
                type,
                AutomaticInvestigationTriggerState.PENDING,
                now,
                now,
                0,
                null,
                null,
                null);
        return repository.insertAutomaticInvestigationTrigger(trigger)
                ? Optional.of(trigger)
                : Optional.empty();
    }

    boolean enabled() {
        String provider = configuration.getProvider();
        return mode != AutomaticInvestigationMode.OFF
                && provider != null
                && !provider.isBlank()
                && !"off".equalsIgnoreCase(provider.trim());
    }
}
