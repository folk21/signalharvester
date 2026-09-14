package io.signalharvester.collection.scheduling;

import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.TaskScheduler;
import io.micronaut.scheduling.annotation.Scheduled;
import io.signalharvester.collection.configuration.CollectionClockFactory;
import io.signalharvester.collection.configuration.CollectionSchedulerConfiguration;
import io.signalharvester.collection.run.CollectionRunRequest;
import io.signalharvester.collection.run.CollectionRunner;
import io.signalharvester.configuration.api.ConfiguredMonitoringProfile;
import io.signalharvester.configuration.api.MonitoringProfileConfigurationProvider;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Polls enabled monitoring profiles and runs due work under PostgreSQL-backed leases. */
@Singleton
@Requires(property = "signalharvester.collection.scheduler.enabled", value = "true")
public final class MonitoringProfileScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(MonitoringProfileScheduler.class);

    private final MonitoringProfileConfigurationProvider profiles;
    private final ProfileScheduleCoordinator schedules;
    private final CollectionRunner runner;
    private final CollectionSchedulerConfiguration configuration;
    private final ExecutorService blockingExecutor;
    private final TaskScheduler taskScheduler;
    private final Clock clock;

    public MonitoringProfileScheduler(
            MonitoringProfileConfigurationProvider profiles,
            ProfileScheduleCoordinator schedules,
            CollectionRunner runner,
            CollectionSchedulerConfiguration configuration,
            @Named(TaskExecutors.BLOCKING) ExecutorService blockingExecutor,
            @Named(TaskExecutors.SCHEDULED) TaskScheduler taskScheduler,
            @Named(CollectionClockFactory.COLLECTION_CLOCK) Clock clock) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.schedules = Objects.requireNonNull(schedules, "schedules");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.blockingExecutor = Objects.requireNonNull(blockingExecutor, "blockingExecutor");
        this.taskScheduler = Objects.requireNonNull(taskScheduler, "taskScheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        validateDurations(configuration.getLeaseDuration(), configuration.getHeartbeatInterval());
    }

    /** Polls persisted enabled profiles and dispatches each successfully claimed run. */
    @Scheduled(
            fixedDelay = "${signalharvester.collection.scheduler.poll-interval:10s}",
            initialDelay = "${signalharvester.collection.scheduler.initial-delay:10s}")
    public void poll() {
        if (!configuration.isEnabled()) {
            return;
        }
        List<ConfiguredMonitoringProfile> enabledProfiles = List.copyOf(profiles.findEnabledProfiles());
        for (ConfiguredMonitoringProfile profile : enabledProfiles) {
            Optional<ProfileScheduleLease> lease = schedules.claim(
                    profile, clock.instant(), configuration.getLeaseDuration());
            lease.ifPresent(value -> blockingExecutor.execute(() -> execute(value)));
        }
    }

    private void execute(ProfileScheduleLease lease) {
        Duration heartbeatInterval = configuration.getHeartbeatInterval();
        ScheduledFuture<?> heartbeat = taskScheduler.scheduleAtFixedRate(
                heartbeatInterval, heartbeatInterval, () -> renew(lease));
        try {
            runner.run(new CollectionRunRequest(lease.profileId(), Optional.empty()));
        } catch (RuntimeException failure) {
            LOG.error("Scheduled collection run failed for profile {}", lease.profileId().value(), failure);
        } finally {
            heartbeat.cancel(false);
            if (!schedules.complete(lease, clock.instant())) {
                LOG.warn("Schedule lease was no longer owned when completing profile {}", lease.profileId().value());
            }
        }
    }

    private void renew(ProfileScheduleLease lease) {
        try {
            if (!schedules.renew(lease, clock.instant(), configuration.getLeaseDuration())) {
                LOG.warn("Schedule lease heartbeat lost ownership for profile {}", lease.profileId().value());
            }
        } catch (RuntimeException failure) {
            LOG.warn("Schedule lease heartbeat failed for profile {}", lease.profileId().value(), failure);
        }
    }

    private static void validateDurations(Duration leaseDuration, Duration heartbeatInterval) {
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("scheduler lease-duration must be positive");
        }
        if (heartbeatInterval.isZero() || heartbeatInterval.isNegative()) {
            throw new IllegalArgumentException("scheduler heartbeat-interval must be positive");
        }
        if (heartbeatInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("scheduler heartbeat-interval must be shorter than lease-duration");
        }
    }
}
