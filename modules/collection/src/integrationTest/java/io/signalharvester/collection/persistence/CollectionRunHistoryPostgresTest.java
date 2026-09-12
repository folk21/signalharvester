package io.signalharvester.collection.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.signalharvester.collection.api.CollectionRunHistory;
import io.signalharvester.collection.api.CollectionRunResult;
import io.signalharvester.collection.api.CollectionRunStatus;
import io.signalharvester.collection.api.CollectionSourceResult;
import io.signalharvester.collection.api.CollectionSourceStatus;
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

@Testcontainers(disabledWithoutDocker = true)
class CollectionRunHistoryPostgresTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("signalharvester")
            .withUsername("signalharvester")
            .withPassword("signalharvester");

    private ApplicationContext context;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        context = ApplicationContext.run(Map.ofEntries(
                Map.entry("datasources.default.url", POSTGRES.getJdbcUrl()),
                Map.entry("datasources.default.username", POSTGRES.getUsername()),
                Map.entry("datasources.default.password", POSTGRES.getPassword()),
                Map.entry("datasources.default.driver-class-name", "org.postgresql.Driver"),
                Map.entry("flyway.datasources.default.enabled", true),
                Map.entry("flyway.datasources.default.locations[0]", "classpath:db/migration/collection"),
                Map.entry("kafka.enabled", false)));
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void shouldPersistAndReadCompletedRunWithOrderedSourceOutcomes() {
        CollectionRunHistoryRecorder recorder = context.getBean(CollectionRunHistoryRecorder.class);
        CollectionRunHistory history = context.getBean(CollectionRunHistory.class);
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        SourceId firstSource = SourceId.of(UUID.fromString("00000000-0000-0000-0000-000000000201"));
        SourceId secondSource = SourceId.of(UUID.fromString("00000000-0000-0000-0000-000000000202"));
        CollectionRunResult result = new CollectionRunResult(
                runId.toString(),
                "profile-admin",
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

    @Test
    void shouldReadRecentRunsInDeterministicOrderWithoutMixingSourceOutcomes() {
        CollectionRunHistoryRecorder recorder = context.getBean(CollectionRunHistoryRecorder.class);
        CollectionRunHistory history = context.getBean(CollectionRunHistory.class);

        CollectionRunResult older = result(
                "00000000-0000-0000-0000-000000000301",
                Instant.parse("2026-09-11T09:00:00Z"),
                List.of(publishedSource("00000000-0000-0000-0000-000000000401", "raw-old", "event-old")));
        CollectionRunResult recentLowerId = result(
                "00000000-0000-0000-0000-000000000302",
                Instant.parse("2026-09-11T12:00:00Z"),
                List.of(
                        publishedSource("00000000-0000-0000-0000-000000000402", "raw-2a", "event-2a"),
                        fetchFailedSource("00000000-0000-0000-0000-000000000403", "HTTP 429")));
        CollectionRunResult recentHigherId = result(
                "00000000-0000-0000-0000-000000000303",
                Instant.parse("2026-09-11T12:00:00Z"),
                List.of(publishedSource("00000000-0000-0000-0000-000000000404", "raw-3", "event-3")));

        recorder.record(older);
        recorder.record(recentLowerId);
        recorder.record(recentHigherId);

        assertEquals(List.of(recentHigherId, recentLowerId), history.recent(2));
    }

    @Test
    void shouldRollBackRunWhenSourceOutcomePersistenceFails() throws Exception {
        CollectionRunHistoryRecorder recorder = context.getBean(CollectionRunHistoryRecorder.class);
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000501");
        UUID rejectedSourceId = UUID.fromString("00000000-0000-0000-0000-000000000502");
        rejectSourceId(rejectedSourceId);

        CollectionRunResult result = result(
                runId.toString(),
                Instant.parse("2026-09-11T13:00:00Z"),
                List.of(publishedSource(rejectedSourceId.toString(), "raw-rejected", "event-rejected")));

        assertThrows(CollectionRunPersistenceException.class, () -> recorder.record(result));
        assertEquals(0, countRunRows(runId));
    }

    @Test
    void shouldRejectStoreAccessOutsideApplicationOwnedTransaction() {
        CollectionRunHistoryStore store = context.getBean(CollectionRunHistoryStore.class);

        CollectionRunPersistenceException exception =
                assertThrows(CollectionRunPersistenceException.class, () -> store.findRecent(10));

        assertTrue(exception.getMessage().contains("Failed to list collection run history"));
    }

    @Test
    void shouldRejectOutOfRangeRecentLimitBeforeQueryingPersistence() {
        CollectionRunHistory history = context.getBean(CollectionRunHistory.class);

        assertThrows(IllegalArgumentException.class, () -> history.recent(0));
        assertThrows(IllegalArgumentException.class,
                () -> history.recent(CollectionRunHistory.MAX_RECENT_LIMIT + 1));
    }

    private static CollectionRunResult result(String runId, Instant startedAt, List<CollectionSourceResult> sources) {
        return new CollectionRunResult(
                runId,
                "profile-admin",
                "JOB",
                startedAt,
                startedAt.plusSeconds(2),
                sources.stream().allMatch(source -> source.status() == CollectionSourceStatus.PUBLISHED)
                        ? CollectionRunStatus.SUCCEEDED
                        : CollectionRunStatus.PARTIALLY_SUCCEEDED,
                sources);
    }

    private static CollectionSourceResult publishedSource(String sourceId, String rawItemId, String eventId) {
        return new CollectionSourceResult(
                SourceId.of(UUID.fromString(sourceId)),
                CollectionSourceStatus.PUBLISHED,
                Optional.of(rawItemId),
                Optional.of(eventId),
                Optional.empty());
    }

    private static CollectionSourceResult fetchFailedSource(String sourceId, String failureMessage) {
        return new CollectionSourceResult(
                SourceId.of(UUID.fromString(sourceId)),
                CollectionSourceStatus.FETCH_FAILED,
                Optional.empty(),
                Optional.empty(),
                Optional.of(failureMessage));
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

    private static void resetDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS collection CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }
}
