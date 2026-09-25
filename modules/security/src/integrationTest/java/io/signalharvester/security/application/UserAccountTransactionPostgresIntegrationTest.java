package io.signalharvester.security.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.signalharvester.security.model.IdentityType;
import io.signalharvester.security.model.UserAccount;
import io.signalharvester.security.model.UserRole;
import io.signalharvester.testing.PostgresContainerSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies PostgreSQL transaction lifecycle for {@link UserAccountManager} last-administrator protection.
 * Feature: {@code SECURITY.IDENTITY_ROLES}.
 */
@Testcontainers(disabledWithoutDocker = true)
class UserAccountTransactionPostgresIntegrationTest {
    private static final String ADMIN_USERNAME = "transaction-admin";
    private static final String ADMIN_PASSWORD = "transaction-admin-password-for-tests";

    @Container
    private static final PostgreSQLContainer POSTGRES = PostgresContainerSupport.create();

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

    /** Allow a successful ADMIN update when another enabled administrator is already committed. */
    @Test
    void shouldAllowTransitionFromTwoEnabledAdministratorsToOne() throws Exception {
        UserAccountOperations operations = context.getBean(UserAccountOperations.class);
        UserAccount firstAdministrator = operations.create(new CreateUserCommand(
                ADMIN_USERNAME + "-first",
                ADMIN_PASSWORD,
                IdentityType.HUMAN,
                true,
                Set.of(UserRole.ADMIN)));
        operations.create(new CreateUserCommand(
                ADMIN_USERNAME + "-second",
                ADMIN_PASSWORD,
                IdentityType.HUMAN,
                true,
                Set.of(UserRole.ADMIN)));
        assertEquals(2L, enabledAdministratorCount(),
                "Both administrators must be committed before the successful update transaction begins");

        UserAccount updated = operations.update(
                firstAdministrator.id(),
                new UpdateUserCommand(false, Set.of(UserRole.ADMIN)));

        assertFalse(updated.enabled());
        assertEquals(1L, enabledAdministratorCount());
    }

    /** Roll back a rejected last-ADMIN update and release its table locks before the next transaction starts. */
    @Test
    void shouldReleaseAdministratorStateLocksAfterRejectedUpdate() throws Exception {
        UserAccountOperations operations = context.getBean(UserAccountOperations.class);
        UserAccount administrator = operations.create(new CreateUserCommand(
                ADMIN_USERNAME,
                ADMIN_PASSWORD,
                IdentityType.HUMAN,
                true,
                Set.of(UserRole.ADMIN)));

        assertThrows(
                LastEnabledAdministratorException.class,
                () -> operations.update(administrator.id(), new UpdateUserCommand(false, Set.of(UserRole.ADMIN))));

        assertEquals(1L, enabledAdministratorCount());
        assertDoesNotThrow(
                UserAccountTransactionPostgresIntegrationTest::acquireIndependentAdministratorWriteLocks,
                "Rejected administrator update must release PostgreSQL table locks before returning");

        UserAccount bot = operations.create(new CreateUserCommand(
                "post-conflict-bot",
                "post-conflict-bot-password-for-tests",
                IdentityType.BOT,
                true,
                Set.of()));
        assertTrue(bot.roles().contains(UserRole.BOT));
    }

    private static void acquireIndependentAdministratorWriteLocks() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("LOCK TABLE security.users, security.user_roles IN ROW EXCLUSIVE MODE NOWAIT");
            } finally {
                connection.rollback();
            }
        }
    }

    private static long enabledAdministratorCount() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT COUNT(DISTINCT u.id)
                          FROM security.users u
                          JOIN security.user_roles r ON r.user_id = u.id
                         WHERE u.enabled = TRUE
                           AND r.role = 'ADMIN'
                        """)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static Map<String, Object> databaseProperties() {
        return Map.ofEntries(
                Map.entry("micronaut.application.name", "signalharvester"),
                Map.entry("micronaut.security.enabled", false),
                Map.entry("datasources.default.url", POSTGRES.getJdbcUrl()),
                Map.entry("datasources.default.username", POSTGRES.getUsername()),
                Map.entry("datasources.default.password", POSTGRES.getPassword()),
                Map.entry("datasources.default.driver-class-name", "org.postgresql.Driver"),
                Map.entry("flyway.datasources.default.enabled", true),
                Map.entry("flyway.datasources.default.locations[0]", "classpath:db/migration/security"),
                Map.entry("signalharvester.security.password-hashing.iterations", 10_000));
    }

    private static void resetDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS security CASCADE");
            statement.execute("DROP TABLE IF EXISTS flyway_schema_history");
        }
    }
}
