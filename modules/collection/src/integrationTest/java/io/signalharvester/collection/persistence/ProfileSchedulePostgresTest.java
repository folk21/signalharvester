package io.signalharvester.collection.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.TaskScheduler;
import io.signalharvester.collection.configuration.CollectionSchedulerConfiguration;
import io.signalharvester.collection.run.CollectionRunner;
import io.signalharvester.collection.scheduling.MonitoringProfileScheduler;
import io.signalharvester.collection.scheduling.ProfileScheduleCoordinator;
import io.signalharvester.collection.scheduling.ProfileScheduleLease;
import io.signalharvester.configuration.api.ConfiguredMonitoringProfile;
import io.signalharvester.configuration.api.MonitoringProfileAnalysisSettings;
import io.signalharvester.configuration.api.MonitoringProfileConfigurationProvider;
import io.signalharvester.configuration.api.MonitoringProfileId;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.testing.PostgresContainerSupport;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies PostgreSQL-backed profile scheduling, including exclusive claims and pre-run lease recovery.
 *
 * <p>Related specification: {@code backend-profile-driven-scheduling}.</p>
 *
 * <p>Feature: {@code COLLECTION.SCHEDULING}.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class ProfileSchedulePostgresTest {

    private static final MonitoringProfileId PROFILE_ID = MonitoringProfileId.of(
            UUID.fromString("00000000-0000-0000-0000-000000000801"));
    private static final SourceId SOURCE_ID = SourceId.of(
            UUID.fromString("00000000-0000-0000-0000-000000000802"));
    private static final Instant START = Instant.parse("2026-09-14T08:00:00Z");
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);

    @Container
    private static final PostgreSQLContainer POSTGRES = PostgresContainerSupport.create();

    private ApplicationContext firstContext;
    private ApplicationContext secondContext;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        firstContext = ApplicationContext.run(databaseProperties());
        secondContext = ApplicationContext.run(databaseProperties());
    }

    @AfterEach
    void tearDown() {
        if (secondContext != null) {
            secondContext.close();
        }
        if (firstContext != null) {
            firstContext.close();
        }
    }

    /** Allow exactly one replica to claim the same due monitoring profile. */
    @Test
    void shouldAllowOnlyOneReplicaToClaimDueProfile() throws Exception {
        ProfileScheduleCoordinator first = firstContext.getBean(ProfileScheduleCoordinator.class);
        ProfileScheduleCoordinator second = secondContext.getBean(ProfileScheduleCoordinator.class);
        ConfiguredMonitoringProfile profile = profile(1);

        assertTrue(first.claim(profile, START, LEASE_DURATION).isEmpty(),
                "first observation initializes next due time instead of running immediately");

        Instant due = START.plus(Duration.ofMinutes(1));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Optional<ProfileScheduleLease>> firstClaim = executor.submit(() -> claim(first, profile, due, ready, start));
            Future<Optional<ProfileScheduleLease>> secondClaim = executor.submit(() -> claim(second, profile, due, ready, start));
            ready.await();
            start.countDown();

            List<Optional<ProfileScheduleLease>> claims = List.of(firstClaim.get(), secondClaim.get());
            assertEquals(1, claims.stream().filter(Optional::isPresent).count());

            ProfileScheduleLease lease = claims.stream().flatMap(Optional::stream).findFirst().orElseThrow();
            ProfileScheduleCoordinator owner = first.renew(lease, due.plusSeconds(10), LEASE_DURATION) ? first : second;
            assertTrue(owner.complete(lease, due.plusSeconds(20)));
            assertTrue(owner.claim(profile, due.plusSeconds(30), LEASE_DURATION).isEmpty());
            assertTrue(owner.claim(profile, due.plus(Duration.ofMinutes(2)), LEASE_DURATION).isPresent());
        }
    }

    /** Reject renewal after lease expiry even when no successor has claimed the row yet. */
    @Test
    void shouldNotRenewExpiredLeaseBeforeSuccessorClaim() {
        ProfileScheduleCoordinator first = firstContext.getBean(ProfileScheduleCoordinator.class);
        ProfileScheduleCoordinator second = secondContext.getBean(ProfileScheduleCoordinator.class);
        ConfiguredMonitoringProfile profile = profile(1);

        assertTrue(first.claim(profile, START, LEASE_DURATION).isEmpty());
        Instant due = START.plus(Duration.ofMinutes(1));
        ProfileScheduleLease expiredLease = first.claim(profile, due, LEASE_DURATION).orElseThrow();
        Instant afterExpiry = due.plus(LEASE_DURATION);

        assertFalse(first.renew(expiredLease, afterExpiry, LEASE_DURATION),
                "expired owner must not resurrect its lease before another replica claims it");
        assertTrue(second.claim(profile, afterExpiry, LEASE_DURATION).isPresent(),
                "expired due work must remain reclaimable after stale renewal is rejected");
    }

    /** Reject completion after lease expiry so stale work cannot advance the due schedule. */
    @Test
    void shouldNotCompleteExpiredLeaseBeforeSuccessorClaim() {
        ProfileScheduleCoordinator first = firstContext.getBean(ProfileScheduleCoordinator.class);
        ProfileScheduleCoordinator second = secondContext.getBean(ProfileScheduleCoordinator.class);
        ConfiguredMonitoringProfile profile = profile(1);

        assertTrue(first.claim(profile, START, LEASE_DURATION).isEmpty());
        Instant due = START.plus(Duration.ofMinutes(1));
        ProfileScheduleLease expiredLease = first.claim(profile, due, LEASE_DURATION).orElseThrow();
        Instant afterExpiry = due.plus(LEASE_DURATION);

        assertFalse(first.complete(expiredLease, afterExpiry),
                "expired owner must not advance next_due_at before another replica claims the row");
        assertTrue(second.claim(profile, afterExpiry, LEASE_DURATION).isPresent(),
                "expired due work must remain reclaimable after stale completion is rejected");
    }

    /** Release a claimed due schedule without advancing its next-due time. */
    @Test
    void shouldReleaseClaimWithoutAdvancingNextDueTime() {
        ProfileScheduleCoordinator first = firstContext.getBean(ProfileScheduleCoordinator.class);
        ProfileScheduleCoordinator second = secondContext.getBean(ProfileScheduleCoordinator.class);
        ConfiguredMonitoringProfile profile = profile(1);

        assertTrue(first.claim(profile, START, LEASE_DURATION).isEmpty());
        Instant due = START.plus(Duration.ofMinutes(1));
        ProfileScheduleLease lease = first.claim(profile, due, LEASE_DURATION).orElseThrow();

        assertTrue(first.release(lease, due.plusSeconds(1)));
        assertTrue(second.claim(profile, due.plusSeconds(2), LEASE_DURATION).isPresent(),
                "released due work must be immediately claimable by another replica");
    }

    /** Keep a successor lease when a stale owner tries to release its expired claim. */
    @Test
    void shouldNotReleaseSuccessorLeaseFromStaleToken() {
        ProfileScheduleCoordinator first = firstContext.getBean(ProfileScheduleCoordinator.class);
        ProfileScheduleCoordinator second = secondContext.getBean(ProfileScheduleCoordinator.class);
        ConfiguredMonitoringProfile profile = profile(1);

        assertTrue(first.claim(profile, START, LEASE_DURATION).isEmpty());
        Instant due = START.plus(Duration.ofMinutes(1));
        ProfileScheduleLease staleLease = first.claim(profile, due, LEASE_DURATION).orElseThrow();
        Instant afterExpiry = due.plus(LEASE_DURATION);
        ProfileScheduleLease successor = second.claim(profile, afterExpiry, LEASE_DURATION).orElseThrow();

        assertFalse(first.release(staleLease, afterExpiry.plusSeconds(1)));
        assertTrue(second.renew(successor, afterExpiry.plusSeconds(2), LEASE_DURATION),
                "stale release must not clear the successor replica lease");
    }

    /** Release a claimed schedule when the blocking executor rejects dispatch before the run starts. */
    @Test
    void shouldReleaseClaimWhenBlockingExecutorRejectsDispatch() {
        ProfileScheduleCoordinator first = firstContext.getBean(ProfileScheduleCoordinator.class);
        ProfileScheduleCoordinator second = secondContext.getBean(ProfileScheduleCoordinator.class);
        ConfiguredMonitoringProfile profile = profile(1);
        Instant due = initializeDueSchedule(first, profile);
        AtomicBoolean runnerCalled = new AtomicBoolean();

        try (ExecutorService rejectingExecutor = Executors.newSingleThreadExecutor()) {
            rejectingExecutor.shutdown();
            MonitoringProfileScheduler scheduler = scheduler(
                    profile, first, rejectingExecutor, scheduledExecutor(firstContext), due, runnerCalled);

            scheduler.poll();
        }

        assertFalse(runnerCalled.get());
        assertTrue(second.claim(profile, due.plusSeconds(1), LEASE_DURATION).isPresent(),
                "dispatch rejection must not strand the due lease until expiry");
    }

    /** Release a claimed schedule when heartbeat setup fails before the collection run starts. */
    @Test
    void shouldReleaseClaimWhenHeartbeatSchedulingFailsBeforeRun() {
        ProfileScheduleCoordinator first = firstContext.getBean(ProfileScheduleCoordinator.class);
        ProfileScheduleCoordinator second = secondContext.getBean(ProfileScheduleCoordinator.class);
        ConfiguredMonitoringProfile profile = profile(1);
        Instant due = initializeDueSchedule(first, profile);
        AtomicBoolean runnerCalled = new AtomicBoolean();

        try (ExecutorService directExecutor = directExecutor()) {
            MonitoringProfileScheduler scheduler = scheduler(
                    profile, first, directExecutor, rejectingTaskScheduler(), due, runnerCalled);

            scheduler.poll();
        }

        assertFalse(runnerCalled.get());
        assertTrue(second.claim(profile, due.plusSeconds(1), LEASE_DURATION).isPresent(),
                "heartbeat setup failure must not strand the due lease until expiry");
    }

    /** Leave an interrupted started run due until lease expiry instead of advancing its schedule. */
    @Test
    void shouldLeaveInterruptedStartedRunDueUntilLeaseExpiry() {
        ProfileScheduleCoordinator first = firstContext.getBean(ProfileScheduleCoordinator.class);
        ProfileScheduleCoordinator second = secondContext.getBean(ProfileScheduleCoordinator.class);
        ConfiguredMonitoringProfile profile = profile(1);
        Instant due = initializeDueSchedule(first, profile);
        AtomicBoolean runnerCalled = new AtomicBoolean();
        CollectionRunner interruptedRunner = request -> {
            runnerCalled.set(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "synthetic scheduled-run interruption",
                    new InterruptedException("synthetic shutdown interruption"));
        };

        try (ExecutorService directExecutor = directExecutor()) {
            MonitoringProfileScheduler scheduler = new MonitoringProfileScheduler(
                    new SingleProfileProvider(profile),
                    first,
                    interruptedRunner,
                    schedulerConfiguration(),
                    directExecutor,
                    scheduledExecutor(firstContext),
                    Clock.fixed(due, java.time.ZoneOffset.UTC));
            try {
                scheduler.poll();
                assertTrue(Thread.currentThread().isInterrupted(),
                        "scheduler must preserve started-run lifecycle interruption");
            } finally {
                Thread.interrupted();
            }
        }

        assertTrue(runnerCalled.get());
        assertTrue(second.claim(profile, due.plus(Duration.ofMinutes(1)), LEASE_DURATION).isEmpty(),
                "interrupted run must not clear the live lease or advance to the next interval");
        assertTrue(second.claim(profile, due.plus(LEASE_DURATION), LEASE_DURATION).isPresent(),
                "interrupted due work must become reclaimable when the original lease expires");
    }

    /** Recalculate next due time when persisted profile interval changes. */
    @Test
    void shouldRescheduleWhenProfileIntervalChanges() {
        ProfileScheduleCoordinator schedules = firstContext.getBean(ProfileScheduleCoordinator.class);

        assertTrue(schedules.claim(profile(5), START, LEASE_DURATION).isEmpty());
        assertTrue(schedules.claim(profile(10), START.plus(Duration.ofMinutes(1)), LEASE_DURATION).isEmpty());
        assertTrue(schedules.claim(profile(10), START.plus(Duration.ofMinutes(5)), LEASE_DURATION).isEmpty());
        assertTrue(schedules.claim(profile(10), START.plus(Duration.ofMinutes(11)), LEASE_DURATION).isPresent());
    }

    private static Instant initializeDueSchedule(
            ProfileScheduleCoordinator schedules,
            ConfiguredMonitoringProfile profile) {
        assertTrue(schedules.claim(profile, START, LEASE_DURATION).isEmpty());
        return START.plus(Duration.ofMinutes(profile.collectionIntervalMinutes()));
    }

    private static MonitoringProfileScheduler scheduler(
            ConfiguredMonitoringProfile profile,
            ProfileScheduleCoordinator schedules,
            ExecutorService blockingExecutor,
            TaskScheduler taskScheduler,
            Instant now,
            AtomicBoolean runnerCalled) {
        CollectionRunner runner = request -> {
            runnerCalled.set(true);
            throw new AssertionError("collection runner must not start when scheduler dispatch setup fails");
        };
        return new MonitoringProfileScheduler(
                new SingleProfileProvider(profile),
                schedules,
                runner,
                schedulerConfiguration(),
                blockingExecutor,
                taskScheduler,
                Clock.fixed(now, java.time.ZoneOffset.UTC));
    }

    private static CollectionSchedulerConfiguration schedulerConfiguration() {
        return new CollectionSchedulerConfiguration() {
            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public Duration getLeaseDuration() {
                return LEASE_DURATION;
            }

            @Override
            public Duration getHeartbeatInterval() {
                return Duration.ofSeconds(30);
            }
        };
    }

    private static TaskScheduler scheduledExecutor(ApplicationContext context) {
        return context.getBean(TaskScheduler.class, Qualifiers.byName(TaskExecutors.SCHEDULED));
    }

    private static TaskScheduler rejectingTaskScheduler() {
        return (TaskScheduler) Proxy.newProxyInstance(
                TaskScheduler.class.getClassLoader(),
                new Class<?>[] {TaskScheduler.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "toString" -> "RejectingTaskScheduler";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new RejectedExecutionException("synthetic scheduler shutdown");
                });
    }

    private static ExecutorService directExecutor() {
        return new AbstractExecutorService() {
            private volatile boolean shutdown;

            @Override
            public void shutdown() {
                shutdown = true;
            }

            @Override
            public List<Runnable> shutdownNow() {
                shutdown = true;
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return shutdown;
            }

            @Override
            public boolean isTerminated() {
                return shutdown;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return shutdown;
            }

            @Override
            public void execute(Runnable command) {
                if (shutdown) {
                    throw new RejectedExecutionException("executor is shut down");
                }
                command.run();
            }
        };
    }

    private record SingleProfileProvider(ConfiguredMonitoringProfile profile)
            implements MonitoringProfileConfigurationProvider {
        @Override
        public Optional<ConfiguredMonitoringProfile> findProfile(MonitoringProfileId profileId) {
            return profile.id().equals(profileId) ? Optional.of(profile) : Optional.empty();
        }

        @Override
        public List<ConfiguredMonitoringProfile> findEnabledProfiles() {
            return List.of(profile);
        }
    }

    private static Optional<ProfileScheduleLease> claim(
            ProfileScheduleCoordinator coordinator,
            ConfiguredMonitoringProfile profile,
            Instant now,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return coordinator.claim(profile, now, LEASE_DURATION);
    }

    private static ConfiguredMonitoringProfile profile(int intervalMinutes) {
        return new ConfiguredMonitoringProfile(
                PROFILE_ID,
                "Scheduled profile",
                "TOPIC",
                true,
                intervalMinutes,
                List.of(SOURCE_ID),
                Map.of(),
                new MonitoringProfileAnalysisSettings(List.of("java"), 1));
    }

    private static Map<String, Object> databaseProperties() {
        return Map.ofEntries(
                Map.entry("datasources.default.url", POSTGRES.getJdbcUrl()),
                Map.entry("datasources.default.username", POSTGRES.getUsername()),
                Map.entry("datasources.default.password", POSTGRES.getPassword()),
                Map.entry("datasources.default.driver-class-name", "org.postgresql.Driver"),
                Map.entry("flyway.datasources.default.enabled", true),
                Map.entry("flyway.datasources.default.locations[0]", "classpath:db/migration/collection"),
                Map.entry("signalharvester.collection.scheduler.enabled", false),
                Map.entry("kafka.enabled", false));
    }

    private static void resetDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS collection CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }
}
