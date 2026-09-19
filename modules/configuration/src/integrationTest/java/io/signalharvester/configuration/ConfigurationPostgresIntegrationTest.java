package io.signalharvester.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceConfigurationProvider;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.api.SourceType;
import io.signalharvester.configuration.application.InvalidSourceConfigurationException;
import io.signalharvester.configuration.application.SourceConfigurationCommand;
import io.signalharvester.configuration.application.SourceConfigurationOperations;
import io.signalharvester.configuration.application.SourceNotFoundException;
import io.signalharvester.configuration.persistence.SourcePersistenceException;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies {@link io.signalharvester.configuration.application.SourceConfigurationManager} with real PostgreSQL,
 * covering Flyway setup, CRUD transactions, rollback behavior, provider reads, and restart durability.
 *
 * <p>Related specification: {@code backend-configuration-persistence-rest}.</p>
 *
 * <p>Feature: {@code CONFIGURATION.SOURCES}.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class ConfigurationPostgresIntegrationTest {

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
     * Migrate empty database.
     */
    @Test
    void shouldMigrateEmptyDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                        SELECT COUNT(*)
                          FROM information_schema.tables
                         WHERE table_schema = 'configuration'
                           AND table_name IN ('sources', 'source_settings')
                        """)) {
            assertTrue(resultSet.next());
            assertEquals(2, resultSet.getInt(1));
        }
    }

    /**
     * Persist CRUD settings and provider reads.
     */
    @Test
    void shouldPersistCrudSettingsAndProviderReads() {
        SourceConfigurationOperations manager = context.getBean(SourceConfigurationOperations.class);
        SourceConfigurationProvider provider = context.getBean(SourceConfigurationProvider.class);

        ConfiguredSource first = manager.create(command(
                "Jobs API",
                URI.create("https://example.test/jobs?language=java"),
                true,
                Map.of("query", "java backend", "region", "eu")));
        ConfiguredSource duplicateName = manager.create(command(
                "Jobs API",
                URI.create("https://another.example.test/jobs"),
                false,
                Map.of()));

        assertNotEquals(first.id(), duplicateName.id());
        assertEquals(2, manager.list().size());
        assertEquals(first, manager.get(first.id()));
        assertEquals(first, provider.findSource(first.id()).orElseThrow());
        assertEquals(1, provider.findEnabledSources().size());
        assertEquals("java backend", provider.findEnabledSources().getFirst().settings().get("query"));

        ConfiguredSource updated = manager.update(first.id(), command(
                "Updated Jobs API",
                URI.create("https://example.test/v2/jobs"),
                false,
                Map.of("query", "kotlin")));

        assertEquals("Updated Jobs API", updated.name());
        assertEquals(Map.of("query", "kotlin"), manager.get(first.id()).settings());
        assertTrue(provider.findEnabledSources().isEmpty());

        manager.delete(first.id());
        assertTrue(provider.findSource(first.id()).isEmpty());
        assertFalse(manager.list().isEmpty());
    }

    /**
     * Rollback source create when settings write fails.
     */
    @Test
    void shouldRollbackSourceCreateWhenSettingsWriteFails() throws Exception {
        SourceConfigurationOperations manager = context.getBean(SourceConfigurationOperations.class);
        rejectSettingValue("reject-me");

        assertThrows(
                SourcePersistenceException.class,
                () -> manager.create(command(
                        "Must Roll Back",
                        URI.create("https://example.test/create"),
                        true,
                        Map.of("query", "reject-me"))));

        assertTrue(manager.list().isEmpty());
        assertEquals(0, countSourceRows());
    }

    /**
     * Rollback source update when settings write fails.
     */
    @Test
    void shouldRollbackSourceUpdateWhenSettingsWriteFails() throws Exception {
        SourceConfigurationOperations manager = context.getBean(SourceConfigurationOperations.class);
        ConfiguredSource original = manager.create(command(
                "Jobs API",
                URI.create("https://example.test/jobs"),
                true,
                Map.of("query", "java")));

        rejectSettingValue("reject-me");

        assertThrows(
                SourcePersistenceException.class,
                () -> manager.update(original.id(), command(
                        "Must Roll Back",
                        URI.create("https://example.test/changed"),
                        false,
                        Map.of("query", "reject-me"))));

        assertEquals(original, manager.get(original.id()));
    }

    /**
     * Read persisted source after application context restart.
     */
    @Test
    void shouldReadPersistedSourceAfterApplicationContextRestart() {
        SourceConfigurationOperations manager = context.getBean(SourceConfigurationOperations.class);
        ConfiguredSource created = manager.create(command(
                "Persistent Source",
                URI.create("https://example.test/persistent"),
                true,
                Map.of("region", "eu")));

        context.close();
        context = ApplicationContext.run(databaseProperties());

        SourceConfigurationProvider provider = context.getBean(SourceConfigurationProvider.class);
        assertEquals(created, provider.findSource(created.id()).orElseThrow());
    }

    /** Reject invalid application commands before any source persistence occurs. */
    @Test
    void shouldValidateSourceApplicationCommandsBeforePersistence() throws Exception {
        SourceConfigurationOperations manager = context.getBean(SourceConfigurationOperations.class);

        assertThrows(
                InvalidSourceConfigurationException.class,
                () -> manager.create(new SourceConfigurationCommand(
                        "   ",
                        SourceType.REST,
                        URI.create("https://example.test/jobs"),
                        true,
                        Map.of())));
        assertThrows(
                InvalidSourceConfigurationException.class,
                () -> manager.create(new SourceConfigurationCommand(
                        "Jobs API",
                        null,
                        URI.create("https://example.test/jobs"),
                        true,
                        Map.of())));

        assertEquals(0L, countSourceRows());
    }

    /**
     * Report not found for missing update and delete.
     */
    @Test
    void shouldReportNotFoundForMissingUpdateAndDelete() {
        SourceConfigurationOperations manager = context.getBean(SourceConfigurationOperations.class);
        SourceId missing = SourceId.of(java.util.UUID.randomUUID());

        assertThrows(
                SourceNotFoundException.class,
                () -> manager.update(missing, command(
                        "Missing",
                        URI.create("https://example.test/missing"),
                        true,
                        Map.of())));
        assertThrows(SourceNotFoundException.class, () -> manager.delete(missing));
    }

    private static void rejectSettingValue(String rejectedValue) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE configuration.source_settings "
                    + "ADD CONSTRAINT test_reject_failed_setting "
                    + "CHECK (setting_value <> '" + rejectedValue + "')");
        }
    }

    private static long countSourceRows() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM configuration.sources")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static SourceConfigurationCommand command(
            String name,
            URI location,
            boolean enabled,
            Map<String, String> settings) {
        return new SourceConfigurationCommand(name, SourceType.REST, location, enabled, settings);
    }

    private static Map<String, Object> databaseProperties() {
        return Map.of(
                "datasources.default.url", POSTGRES.getJdbcUrl(),
                "datasources.default.username", POSTGRES.getUsername(),
                "datasources.default.password", POSTGRES.getPassword(),
                "datasources.default.driver-class-name", "org.postgresql.Driver",
                "flyway.datasources.default.enabled", true,
                "flyway.datasources.default.locations[0]", "classpath:db/migration/configuration");
    }

    private static void resetDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS configuration CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }
}
