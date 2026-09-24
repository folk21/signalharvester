package io.signalharvester.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.operations.api.OperationalChangeCategory;
import io.signalharvester.operations.api.OperationalChangeContext;
import io.signalharvester.operations.api.OperationalChangeJournal;
import io.signalharvester.operations.api.OperationalChangeOutcome;
import io.signalharvester.operations.api.OperationalChangeRequest;
import io.signalharvester.operations.api.OperationalChangeTargetType;
import io.signalharvester.operations.application.OperationalIntelligenceOperations;
import io.signalharvester.operations.model.HealthStatus;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies Operations-owned change/snapshot persistence and bounded report/correlation reads with PostgreSQL.
 *
 * <p>Related specification: {@code backend-observability-intelligence}.</p>
 *
 * <p>Features: {@code OPERATIONS.CHANGE_JOURNAL}, {@code OBSERVABILITY.HEALTH_INTELLIGENCE}.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class OperationalIntelligencePostgresIntegrationTest {

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
                Map.entry("flyway.datasources.default.locations[0]", "classpath:db/migration/operations"),
                Map.entry("signalharvester.build.version", "test-build"),
                Map.entry("signalharvester.operations.health-snapshot-retention-count", 2)));
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    /** Persist a sanitized change and expose it through the bounded operational timeline. */
    @Test
    void shouldPersistOperationalChange() throws Exception {
        OperationalChangeJournal journal = context.getBean(OperationalChangeJournal.class);
        OperationalIntelligenceOperations operations = context.getBean(OperationalIntelligenceOperations.class);

        var recorded = journal.record(new OperationalChangeRequest(
                OperationalChangeCategory.DEPLOYMENT_TUNING,
                OperationalChangeTargetType.DEPLOYMENT,
                "signalharvester-backend",
                Map.of("replicas", "1"),
                Map.of("replicas", "3"),
                OperationalChangeOutcome.APPLIED,
                OperationalChangeContext.tooling("test-operator", "request-1")));

        assertEquals(1, operations.recentChanges(10).size());
        assertEquals(recorded.id(), operations.recentChanges(10).getFirst().id());
        assertEquals(1L, scalarLong("SELECT count(*) FROM operations.change_journal"));
    }

    /** Keep an applied mutation and its journal record in the same caller-owned transaction. */
    @Test
    @SuppressWarnings("unchecked")
    void shouldRollbackCurrentTransactionJournalRecordWithCaller() throws Exception {
        OperationalChangeJournal journal = context.getBean(OperationalChangeJournal.class);
        TransactionOperations<Connection> transactions = (TransactionOperations<Connection>) context.getBean(
                TransactionOperations.class, Qualifiers.byName("default"));
        OperationalChangeRequest request = new OperationalChangeRequest(
                OperationalChangeCategory.SOURCE_CONFIGURATION,
                OperationalChangeTargetType.SOURCE,
                "source-transaction-test",
                Map.of(),
                Map.of("enabled", "true"),
                OperationalChangeOutcome.APPLIED,
                OperationalChangeContext.rest("test-operator", "request-transaction"));

        assertThrows(IllegalStateException.class, () -> transactions.executeWrite(status -> {
            journal.recordInCurrentTransaction(request);
            throw new IllegalStateException("force rollback");
        }));

        assertEquals(0L, scalarLong("SELECT count(*) FROM operations.change_journal"));
    }

    /** Capture an honest UNKNOWN foundation snapshot and render bounded manual-analysis evidence. */
    @Test
    void shouldCaptureFoundationSnapshotAndReportChangeCorrelation() {
        OperationalChangeJournal journal = context.getBean(OperationalChangeJournal.class);
        OperationalIntelligenceOperations operations = context.getBean(OperationalIntelligenceOperations.class);
        var change = journal.record(new OperationalChangeRequest(
                OperationalChangeCategory.TEST_SCENARIO,
                OperationalChangeTargetType.SCENARIO,
                "capacity-baseline",
                Map.of(),
                Map.of("replicas", "3"),
                OperationalChangeOutcome.APPLIED,
                OperationalChangeContext.tooling("test-harness", "scenario-1")));

        var snapshot = operations.captureFoundationSnapshot();
        var correlation = operations.correlateChange(change.id());
        String report = operations.latestMarkdownReport();

        assertEquals(HealthStatus.UNKNOWN, snapshot.overallStatus());
        assertEquals(0, snapshot.healthScore());
        assertFalse(snapshot.evidenceComplete());
        assertTrue(snapshot.recentChangeIds().contains(change.id()));
        assertNotNull(correlation.after());
        assertEquals(snapshot.id(), correlation.after().id());
        assertTrue(report.contains("SignalHarvester Health Report"));
        assertTrue(report.contains("TEST_SCENARIO"));
        assertTrue(report.contains("deterministic-statistical-health-engine-not-active"));
    }

    /** Keep snapshot history bounded by the configured count while preserving the newest evidence. */
    @Test
    void shouldEnforceSnapshotRetentionCount() throws Exception {
        OperationalIntelligenceOperations operations = context.getBean(OperationalIntelligenceOperations.class);

        operations.captureFoundationSnapshot();
        operations.captureFoundationSnapshot();
        var latest = operations.captureFoundationSnapshot();

        assertEquals(2L, scalarLong("SELECT count(*) FROM operations.health_snapshots"));
        assertEquals(latest.id(), operations.latestSnapshot().id());
    }

    private static long scalarLong(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static void resetDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS operations CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }
}
