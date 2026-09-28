package io.signalharvester.operations.assisted;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.operations.model.IncidentAssessment;
import io.signalharvester.operations.persistence.OperationalIntelligenceRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Plans, leases, and completes durable automatic assisted-investigation work. */
@Singleton
public final class AutomaticInvestigationCoordinator {
    private static final int MAX_ERROR_CHARS = 1000;

    private final OperationalIntelligenceRepository repository;
    private final TransactionOperations<Connection> transactions;
    private final AssistedInvestigationService assisted;
    private final AssistedInvestigationConfiguration configuration;
    private final AutomaticInvestigationPlanner planner;
    private final Clock clock;

    public AutomaticInvestigationCoordinator(
            OperationalIntelligenceRepository repository,
            @Named("default") TransactionOperations<Connection> transactions,
            AssistedInvestigationService assisted,
            AssistedInvestigationConfiguration configuration,
            AutomaticInvestigationPlanner planner) {
        this(repository, transactions, assisted, configuration, planner, Clock.systemUTC());
    }

    AutomaticInvestigationCoordinator(
            OperationalIntelligenceRepository repository,
            TransactionOperations<Connection> transactions,
            AssistedInvestigationService assisted,
            AssistedInvestigationConfiguration configuration,
            AutomaticInvestigationPlanner planner,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.assisted = Objects.requireNonNull(assisted, "assisted");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.clock = Objects.requireNonNull(clock, "clock");
        validateDurations(configuration);
    }

    /** Returns whether automatic provider execution is enabled by both mode and provider configuration. */
    public boolean automaticExecutionEnabled() {
        return planner.enabled();
    }

    /** Plans at most one trigger for the latest Health Snapshot under a cluster-wide transaction lock. */
    public Optional<AutomaticInvestigationTrigger> planLatestTrigger() {
        return planner.planLatest();
    }

    /** Claims one due trigger using an exact expiring token. */
    public Optional<ClaimedAutomaticInvestigation> claimNext() {
        Instant now = clock.instant();
        UUID leaseToken = UUID.randomUUID();
        return transactions.executeWrite(status -> repository.claimAutomaticInvestigationTrigger(
                now,
                leaseToken,
                now.plus(configuration.getAutomaticLeaseDuration())));
    }

    /** Renews one still-live exact trigger lease. */
    public boolean renew(ClaimedAutomaticInvestigation claim) {
        Instant now = clock.instant();
        return transactions.executeWrite(status -> repository.renewAutomaticInvestigationTriggerLease(
                claim.triggerId(),
                claim.leaseToken(),
                now,
                now.plus(configuration.getAutomaticLeaseDuration())));
    }

    /** Runs provider/tool work without a database transaction. */
    public IncidentAssessment investigate(ClaimedAutomaticInvestigation claim) {
        return assisted.investigateAutomatic(claim.snapshotId());
    }

    /** Atomically persists one validated automatic assessment and completes its exact live trigger lease. */
    public void complete(ClaimedAutomaticInvestigation claim, IncidentAssessment assessment) {
        Instant completedAt = clock.instant();
        transactions.executeWrite(status -> {
            assisted.persistInCurrentTransaction(assessment);
            repository.completeAutomaticInvestigationTrigger(
                    claim.triggerId(), claim.leaseToken(), completedAt, assessment.id());
            return null;
        });
    }

    /** Records a retry or terminal exhaustion for one failed still-owned trigger. */
    public void fail(ClaimedAutomaticInvestigation claim, RuntimeException failure) {
        Instant failedAt = clock.instant();
        boolean exhausted = claim.attemptCount() >= configuration.getAutomaticMaxAttempts();
        String lastError = boundedMessage(failure);
        transactions.executeWrite(status -> {
            if (exhausted) {
                repository.exhaustAutomaticInvestigationTrigger(
                        claim.triggerId(), claim.leaseToken(), failedAt, lastError);
            } else {
                repository.retryAutomaticInvestigationTrigger(
                        claim.triggerId(),
                        claim.leaseToken(),
                        failedAt,
                        failedAt.plus(configuration.getAutomaticRetryBackoff()),
                        lastError);
            }
            return null;
        });
    }

    /** Returns bounded durable trigger history for operator inspection. */
    public List<AutomaticInvestigationTrigger> recentTriggers(int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        return transactions.executeRead(status -> repository.findRecentAutomaticInvestigationTriggers(bounded));
    }

    private static String boundedMessage(RuntimeException failure) {
        String message = failure.getMessage();
        String value = failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        return value.length() <= MAX_ERROR_CHARS ? value : value.substring(0, MAX_ERROR_CHARS);
    }

    private static void validateDurations(AssistedInvestigationConfiguration configuration) {
        requirePositive(configuration.getAutomaticInitialDelay(), "automaticInitialDelay");
        requirePositive(configuration.getAutomaticPollInterval(), "automaticPollInterval");
        requirePositive(configuration.getAutomaticCooldown(), "automaticCooldown");
        requirePositive(configuration.getPeriodicReassessmentInterval(), "periodicReassessmentInterval");
        requirePositive(configuration.getAutomaticLeaseDuration(), "automaticLeaseDuration");
        requirePositive(configuration.getAutomaticLeaseHeartbeatInterval(), "automaticLeaseHeartbeatInterval");
        requirePositive(configuration.getAutomaticRetryBackoff(), "automaticRetryBackoff");
        if (configuration.getAutomaticLeaseHeartbeatInterval().compareTo(configuration.getAutomaticLeaseDuration()) >= 0) {
            throw new IllegalArgumentException(
                    "automaticLeaseHeartbeatInterval must be shorter than automaticLeaseDuration");
        }
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
