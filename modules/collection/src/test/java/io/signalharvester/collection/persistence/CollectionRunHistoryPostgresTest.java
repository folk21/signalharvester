package io.signalharvester.collection.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.signalharvester.collection.run.CollectionRunHistoryStore;
import io.signalharvester.collection.run.CollectionRunResult;
import io.signalharvester.collection.run.CollectionRunStatus;
import io.signalharvester.collection.run.CollectionSourceResult;
import io.signalharvester.collection.run.CollectionSourceStatus;
import io.signalharvester.configuration.api.SourceId;
import java.sql.Connection;
import java.sql.DriverManager;
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
        CollectionRunHistoryStore store = context.getBean(CollectionRunHistoryStore.class);
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

        store.save(result);

        assertEquals(result, store.findById(runId.toString()).orElseThrow());
        assertEquals(List.of(result), store.findRecent(10));
        assertTrue(store.findById(UUID.randomUUID().toString()).isEmpty());
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
