package io.signalharvester.operations.assisted;

import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.TaskScheduler;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Plans and dispatches automatic investigations without blocking the shared scheduled executor on model I/O. */
@Singleton
@Requires(
        property = "signalharvester.operations.assisted-investigation.automatic-mode",
        notEquals = "off",
        defaultValue = "off")
public final class AutomaticInvestigationScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(AutomaticInvestigationScheduler.class);

    private final AutomaticInvestigationCoordinator coordinator;
    private final AssistedInvestigationConfiguration configuration;
    private final ExecutorService blockingExecutor;
    private final TaskScheduler taskScheduler;
    private final AtomicBoolean localWorkerActive = new AtomicBoolean();

    public AutomaticInvestigationScheduler(
            AutomaticInvestigationCoordinator coordinator,
            AssistedInvestigationConfiguration configuration,
            @Named(TaskExecutors.BLOCKING) ExecutorService blockingExecutor,
            @Named(TaskExecutors.SCHEDULED) TaskScheduler taskScheduler) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.blockingExecutor = Objects.requireNonNull(blockingExecutor, "blockingExecutor");
        this.taskScheduler = Objects.requireNonNull(taskScheduler, "taskScheduler");
    }

    /** Plans cluster-wide eligible work and starts at most one local provider investigation at a time. */
    @Scheduled(
            fixedDelay = "${signalharvester.operations.assisted-investigation.automatic-poll-interval:30s}",
            initialDelay = "${signalharvester.operations.assisted-investigation.automatic-initial-delay:45s}")
    public void poll() {
        if (!coordinator.automaticExecutionEnabled()) {
            return;
        }
        try {
            coordinator.planLatestTrigger();
        } catch (RuntimeException failure) {
            LOG.warn("Failed to plan automatic assisted investigation", failure);
        }
        if (!localWorkerActive.compareAndSet(false, true)) {
            return;
        }
        ClaimedAutomaticInvestigation claim;
        try {
            var claimed = coordinator.claimNext();
            if (claimed.isEmpty()) {
                localWorkerActive.set(false);
                return;
            }
            claim = claimed.get();
        } catch (RuntimeException failure) {
            localWorkerActive.set(false);
            LOG.warn("Failed to claim automatic assisted investigation", failure);
            return;
        }
        try {
            blockingExecutor.execute(() -> execute(claim));
        } catch (RuntimeException failure) {
            localWorkerActive.set(false);
            try {
                coordinator.fail(claim, failure);
            } catch (RuntimeException persistenceFailure) {
                failure.addSuppressed(persistenceFailure);
            }
            LOG.warn("Blocking executor rejected automatic assisted investigation dispatch", failure);
        }
    }

    private void execute(ClaimedAutomaticInvestigation claim) {
        ScheduledFuture<?> heartbeat = null;
        boolean interrupted = false;
        try {
            heartbeat = taskScheduler.scheduleAtFixedRate(
                    configuration.getAutomaticLeaseHeartbeatInterval(),
                    configuration.getAutomaticLeaseHeartbeatInterval(),
                    () -> renew(claim));
            var assessment = coordinator.investigate(claim);
            coordinator.complete(claim, assessment);
            LOG.info(
                    "Completed automatic assisted investigation triggerId={} snapshotId={} type={} attempt={}",
                    claim.triggerId(), claim.snapshotId(), claim.type(), claim.attemptCount());
        } catch (RuntimeException failure) {
            interrupted = Thread.currentThread().isInterrupted() || hasInterruptedCause(failure);
            if (interrupted) {
                Thread.currentThread().interrupt();
                LOG.warn(
                        "Automatic assisted investigation interrupted triggerId={}; leaving lease to expiry recovery",
                        claim.triggerId(),
                        failure);
            } else {
                try {
                    coordinator.fail(claim, failure);
                } catch (RuntimeException persistenceFailure) {
                    failure.addSuppressed(persistenceFailure);
                }
                LOG.warn(
                        "Automatic assisted investigation failed triggerId={} snapshotId={} attempt={}",
                        claim.triggerId(), claim.snapshotId(), claim.attemptCount(), failure);
            }
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            localWorkerActive.set(false);
        }
    }

    private void renew(ClaimedAutomaticInvestigation claim) {
        try {
            if (!coordinator.renew(claim)) {
                LOG.warn("Automatic investigation lease heartbeat lost ownership triggerId={}", claim.triggerId());
            }
        } catch (RuntimeException failure) {
            LOG.warn("Automatic investigation lease heartbeat failed triggerId={}", claim.triggerId(), failure);
        }
    }

    private static boolean hasInterruptedCause(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
