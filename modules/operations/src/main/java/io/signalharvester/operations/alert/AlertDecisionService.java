package io.signalharvester.operations.alert;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.operations.model.HealthSnapshot;
import io.signalharvester.operations.model.IncidentAssessment;
import io.signalharvester.operations.persistence.OperationalIntelligenceRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Applies deterministic human-attention alert decisions and persists their lifecycle. */
@Singleton
public final class AlertDecisionService implements AlertDecisionOperations {
    private final OperationalIntelligenceRepository repository;
    private final TransactionOperations<Connection> transactions;
    private final AlertPolicyConfiguration configuration;
    private final AlertDecisionPolicy policy;

    public AlertDecisionService(
            OperationalIntelligenceRepository repository,
            @Named("default") TransactionOperations<Connection> transactions,
            AlertPolicyConfiguration configuration,
            AlertDecisionPolicy policy) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.policy = Objects.requireNonNull(policy, "policy");
        validateConfiguration(configuration);
    }

    /** Evaluates one already-persisted snapshot inside the caller-owned transaction. */
    public void evaluateSnapshotInCurrentTransaction(HealthSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!configuration.isEnabled()) {
            return;
        }
        repository.acquireHumanAttentionAlertDecisionLock();
        int historyLimit = Math.max(
                Math.max(configuration.getDegradedMinConsecutiveSnapshots(), configuration.getUnhealthyMinConsecutiveSnapshots()),
                configuration.getHealthyMinConsecutiveSnapshotsToResolve());
        List<HealthSnapshot> previous = repository.findRecentSnapshotsBefore(snapshot.generatedAt(), Math.max(0, historyLimit - 1));
        HumanAttentionAlert active = repository.findOpenHumanAttentionAlert().orElse(null);
        HumanAttentionAlert latestResolved = repository.findLatestResolvedHumanAttentionAlert().orElse(null);
        AlertDecision decision = policy.evaluate(
                snapshot,
                previous,
                active,
                latestResolved,
                snapshot.generatedAt(),
                configuration.getDegradedMinConsecutiveSnapshots(),
                configuration.getUnhealthyMinConsecutiveSnapshots(),
                configuration.getHealthyMinConsecutiveSnapshotsToResolve(),
                configuration.getReopenCooldown());

        if (decision instanceof AlertDecision.Open open) {
            repository.insertHumanAttentionAlert(new HumanAttentionAlert(
                    UUID.randomUUID(),
                    HumanAttentionAlertState.OPEN,
                    open.severity(),
                    open.reason(),
                    configuration.getPolicyVersion(),
                    snapshot.generatedAt(),
                    snapshot.generatedAt(),
                    null,
                    snapshot.id(),
                    snapshot.id(),
                    snapshot.overallStatus(),
                    snapshot.healthScore(),
                    null,
                    null));
        } else if (decision instanceof AlertDecision.Update update && active != null) {
            repository.updateHumanAttentionAlertObservation(
                    active.id(),
                    update.severity(),
                    update.reason(),
                    snapshot.generatedAt(),
                    snapshot.id(),
                    snapshot.overallStatus(),
                    snapshot.healthScore());
        } else if (decision instanceof AlertDecision.Resolve && active != null) {
            repository.resolveHumanAttentionAlert(
                    active.id(),
                    snapshot.generatedAt(),
                    snapshot.id(),
                    snapshot.overallStatus(),
                    snapshot.healthScore());
        }
        repository.deleteResolvedHumanAttentionAlertsBeyond(configuration.getRetentionCount());
    }

    /** Adds advisory structured-model context to an already-open incident without changing alert authority. */
    public void attachAssessmentInCurrentTransaction(IncidentAssessment assessment) {
        Objects.requireNonNull(assessment, "assessment");
        if (!configuration.isEnabled()) {
            return;
        }
        HealthSnapshot snapshot = repository.findSnapshot(assessment.snapshotId()).orElse(null);
        HumanAttentionAlert active = repository.findOpenHumanAttentionAlert().orElse(null);
        if (snapshot == null || active == null || snapshot.generatedAt().isBefore(active.openedAt())) {
            return;
        }
        repository.attachAssessmentToHumanAttentionAlert(
                active.id(), assessment.id(), assessment.humanAttentionSuggested());
    }

    @Override
    public List<HumanAttentionAlert> recentAlerts(int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        return transactions.executeRead(status -> repository.findRecentHumanAttentionAlerts(bounded));
    }

    private static void validateConfiguration(AlertPolicyConfiguration configuration) {
        String policyVersion = configuration.getPolicyVersion();
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("alert policyVersion must not be blank");
        }
        Duration cooldown = Objects.requireNonNull(configuration.getReopenCooldown(), "reopenCooldown");
        if (cooldown.isNegative()) {
            throw new IllegalArgumentException("reopenCooldown must not be negative");
        }
    }
}
