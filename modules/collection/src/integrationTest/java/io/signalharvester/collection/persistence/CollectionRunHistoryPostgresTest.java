package io.signalharvester.collection.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.signalharvester.collection.run.CollectionRunHistory;
import io.signalharvester.collection.run.CollectionRunResult;
import io.signalharvester.collection.run.CollectionRunStatus;
import io.signalharvester.collection.run.CollectionSourceResult;
import io.signalharvester.collection.run.CollectionSourceStatus;
import io.signalharvester.collection.run.CollectionRunHistoryRecorder;
import io.signalharvester.collection.run.CollectionRunHistoryStore;
import io.signalharvester.configuration.api.SourceId;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies PostgreSQL persistence used by {@link JdbcCollectionRunHistoryStore}, including transaction
 * participation, atomic writes, deterministic recent-history ordering, and restart durability.
 *
 * <p>Related specification: {@code backend-collection-run-orchestration}.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class CollectionRunHistoryPostgresTest {

    private static final String PROFILE_ID = "profile-admin";
    private static final UUID ORDERED_RUN_ID = uuid("00000000-0000-0000-0000-000000000101");
    private static final UUID ORDERED_FIRST_SOURCE_ID = uuid("00000000-0000-0000-0000-000000000201");
    private static final UUID ORDERED_SECOND_SOURCE_ID = uuid("00000000-0000-0000-0000-000000000202");
    private static final UUID OLDER_RUN_ID = uuid("00000000-0000-0000-0000-000000000301");
    private static final UUID RECENT_LOWER_RUN_ID = uuid("00000000-0000-0000-0000-000000000302");
    private static final UUID RECENT_HIGHER_RUN_ID = uuid("00000000-0000-0000-0000-000000000303");
    private static final UUID OLDER_SOURCE_ID = uuid("00000000-0000-0000-0000-000000000401");
    private static final UUID RECENT_FIRST_SOURCE_ID = uuid("00000000-0000-0000-0000-000000000402");
    private static final UUID RECENT_FAILED_SOURCE_ID = uuid("00000000-0000-0000-0000-000000000403");
    private static final UUID RECENT_HIGHER_SOURCE_ID = uuid("00000000-0000-0000-0000-000000000404");
    private static final UUID ROLLBACK_RUN_ID = uuid("00000000-0000-0000-0000-000000000501");
    private static final UUID REJECTED_SOURCE_ID = uuid("00000000-0000-0000-0000-000000000502");
    private static final UUID RESTART_RUN_ID = uuid("00000000-0000-0000-0000-000000000601");
    private static final UUID RESTART_SOURCE_ID = uuid("00000000-0000-0000-0000-000000000602");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("signalharvester")
            .withUsername("signalharvester")
            .withPassword("signalharvester");

    private ApplicationContext context;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        context = ApplicationContext.run(databaseProperties());
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    /**
     * Persist and read completed run with ordered source outcomes.
     */
    @Test
    void shouldPersistAndReadCompletedRunWithOrderedSourceOutcomes() {
        CollectionRunHistoryRecorder recorder = context.getBean(CollectionRunHistoryRecorder.class);
        CollectionRunHistory history = context.getBean(CollectionRunHistory.class);
        UUID runId = ORDERED_RUN_ID;
        SourceId firstSource = SourceId.of(ORDERED_FIRST_SOURCE_ID);
        SourceId secondSource = SourceId.of(ORDERED_SECOND_SOURCE_ID);
        CollectionRunResult result = new CollectionRunResult(
                runId.toString(),
                PROFILE_ID,
                "JOB",
                Instant.parse("2026-09-11T10:00:00Z"),
                Instant.parse("2026-09-11T10:00:02Z"),
                CollectionRunStatus.PARTIALLY_SUCCEEDED,
                List.of(
                        new CollectionSourceResult(
                                firstSource,
                                CollectionSourceStatus.PUBLISHED,
                                Optional.of("raw-1"),
                                Optional.of("event-1"),
                                Optional.empty()),
                        new CollectionSourceResult(
                                secondSource,
                                CollectionSourceStatus.FETCH_FAILED,
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of("HTTP 503"))));

        recorder.record(result);

        assertEquals(result, history.get(runId));
        assertEquals(List.of(result), history.recent(10));
    }

    /**
     * Read recent runs in deterministic order without mixing source outcomes.
     */
    @Test
    void shouldReadRecentRunsInDeterministicOrderWithoutMixingSourceOutcomes() {
        CollectionRunHistoryRecorder recorder = context.getBean(CollectionRunHistoryRecorder.class);
        CollectionRunHistory history = context.getBean(CollectionRunHistory.class);

        CollectionRunResult older = result(
                OLDER_RUN_ID,
                Instant.parse("2026-09-11T09:00:00Z"),
                List.of(publishedSource(OLDER_SOURCE_ID, "raw-old", "event-old")));
        CollectionRunResult recentLowerId = result(
                RECENT_LOWER_RUN_ID,
                Instant.parse("2026-09-11T12:00:00Z"),
                List.of(
                        publishedSource(RECENT_FIRST_SOURCE_ID, "raw-2a", "event-2a"),
                        fetchFailedSource(RECENT_FAILED_SOURCE_ID, "HTTP 429")));
        CollectionRunResult recentHigherId = result(
                RECENT_HIGHER_RUN_ID,
                Instant.parse("2026-09-11T12:00:00Z"),
                List.of(publishedSource(RECENT_HIGHER_SOURCE_ID, "raw-3", "event-3")));

        recorder.record(older);
        recorder.record(recentLowerId);
        recorder.record(recentHigherId);

        assertEquals(List.of(recentHigherId, recentLowerId), history.recent(2));
    }

    /**
     * Read persisted history after application context restart.
     */
    @Test
    void shouldReadPersistedHistoryAfterApplicationContextRestart() {
        CollectionRunHistoryRecorder recorder = context.getBean(CollectionRunHistoryRecorder.class);
        UUID runId = RESTART_RUN_ID;
        CollectionRunResult persisted = result(
                runId,
                Instant.parse("2026-09-11T14:00:00Z"),
                List.of(publishedSource(
                        RESTART_SOURCE_ID,
                        "raw-restart",
                        "event-restart")));
        recorder.record(persisted);

        context.close();
        context = ApplicationContext.run(databaseProperties());

        CollectionRunHistory history = context.getBean(CollectionRunHistory.class);
        assertEquals(persisted, history.get(runId));
    }

    /**
     * Roll back run when source outcome persistence fails.
     */
    @Test
    void shouldRollBackRunWhenSourceOutcomePersistenceFails() throws Exception {
        CollectionRunHistoryRecorder recorder = context.getBean(CollectionRunHistoryRecorder.class);
        UUID runId = ROLLBACK_RUN_ID;
        UUID rejectedSourceId = REJECTED_SOURCE_ID;
        rejectSourceId(rejectedSourceId);

        CollectionRunResult result = result(
                runId,
                Instant.parse("2026-09-11T13:00:00Z"),
                List.of(publishedSource(rejectedSourceId, "raw-rejected", "event-rejected")));

        assertThrows(CollectionRunPersistenceException.class, () -> recorder.record(result));
        assertEquals(0, countRunRows(runId));
    }

    /**
     * Reject store access outside application-owned transaction.
     */
    @Test
    void shouldRejectStoreAccessOutsideApplicationOwnedTransaction() {
        CollectionRunHistoryStore store = context.getBean(CollectionRunHistoryStore.class);

        CollectionRunPersistenceException exception =
                assertThrows(CollectionRunPersistenceException.class, () -> store.findRecent(10));

        assertTrue(exception.getMessage().contains("Failed to list collection run history"));
    }

    /**
     * Reject out-of-range recent limit before querying persistence.
     */
    @Test
    void shouldRejectOutOfRangeRecentLimitBeforeQueryingPersistence() {
        CollectionRunHistory history = context.getBean(CollectionRunHistory.class);

        assertThrows(IllegalArgumentException.class, () -> history.recent(0));
        assertThrows(IllegalArgumentException.class,
                () -> history.recent(CollectionRunHistory.MAX_RECENT_LIMIT + 1));
    }

    private static CollectionRunResult result(UUID runId, Instant startedAt, List<CollectionSourceResult> sources) {
        return new CollectionRunResult(
                runId.toString(),
                PROFILE_ID,
                "JOB",
                startedAt,
                startedAt.plusSeconds(2),
                sources.stream().allMatch(source -> source.status() == CollectionSourceStatus.PUBLISHED)
                        ? CollectionRunStatus.SUCCEEDED
                        : CollectionRunStatus.PARTIALLY_SUCCEEDED,
                sources);
    }

    private static CollectionSourceResult publishedSource(UUID sourceId, String rawItemId, String eventId) {
        return new CollectionSourceResult(
                SourceId.of(sourceId),
                CollectionSourceStatus.PUBLISHED,
                Optional.of(rawItemId),
                Optional.of(eventId),
                Optional.empty());
    }

    private static CollectionSourceResult fetchFailedSource(UUID sourceId, String failureMessage) {
        return new CollectionSourceResult(
                SourceId.of(sourceId),
                CollectionSourceStatus.FETCH_FAILED,
                Optional.empty(),
                Optional.empty(),
                Optional.of(failureMessage));
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static void rejectSourceId(UUID sourceId) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE collection.collection_run_sources "
                    + "ADD CONSTRAINT reject_test_source "
                    + "CHECK (source_id <> '" + sourceId + "'::uuid)");
        }
    }

    private static long countRunRows(UUID runId) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM collection.collection_runs WHERE collection_run_id = ?")) {
            statement.setObject(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static Map<String, Object> databaseProperties() {
        return Map.ofEntries(
                Map.entry("datasources.default.url", POSTGRES.getJdbcUrl()),
                Map.entry("datasources.default.username", POSTGRES.getUsername()),
                Map.entry("datasources.default.password", POSTGRES.getPassword()),
                Map.entry("datasources.default.driver-class-name", "org.postgresql.Driver"),
                Map.entry("flyway.datasources.default.enabled", true),
                Map.entry("flyway.datasources.default.locations[0]", "classpath:db/migration/collection"),
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
