package io.signalharvester.collection.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.signalharvester.collection.scheduling.ProfileScheduleCoordinator;
import io.signalharvester.collection.scheduling.ProfileScheduleLease;
import io.signalharvester.configuration.api.ConfiguredMonitoringProfile;
import io.signalharvester.configuration.api.MonitoringProfileAnalysisSettings;
import io.signalharvester.configuration.api.MonitoringProfileId;
import io.signalharvester.configuration.api.SourceId;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies PostgreSQL-backed profile scheduling, especially exclusive due-work claims across replicas.
 *
 * <p>Related specification: {@code backend-profile-driven-scheduling}.</p>
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
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("signalharvester")
            .withUsername("signalharvester")
            .withPassword("signalharvester");

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

    /** Recalculate next due time when persisted profile interval changes. */
    @Test
    void shouldRescheduleWhenProfileIntervalChanges() {
        ProfileScheduleCoordinator schedules = firstContext.getBean(ProfileScheduleCoordinator.class);

        assertTrue(schedules.claim(profile(5), START, LEASE_DURATION).isEmpty());
        assertTrue(schedules.claim(profile(10), START.plus(Duration.ofMinutes(1)), LEASE_DURATION).isEmpty());
        assertTrue(schedules.claim(profile(10), START.plus(Duration.ofMinutes(5)), LEASE_DURATION).isEmpty());
        assertTrue(schedules.claim(profile(10), START.plus(Duration.ofMinutes(11)), LEASE_DURATION).isPresent());
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
