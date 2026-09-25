package io.signalharvester.testing;

import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Builds the repository-standard PostgreSQL Testcontainer with transport and JDBC readiness. */
public final class PostgresContainerSupport {
    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final String DATABASE_NAME = "signalharvester";
    private static final String USERNAME = "signalharvester";
    private static final String PASSWORD = "signalharvester";
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration JDBC_POLL_INTERVAL = Duration.ofMillis(100);

    private PostgresContainerSupport() {
    }

    /** Creates PostgreSQL whose start lifecycle completes only after an authenticated JDBC probe succeeds. */
    public static PostgreSQLContainer create() {
        PostgreSQLContainer container = new JdbcReadyPostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName(DATABASE_NAME)
                .withUsername(USERNAME)
                .withPassword(PASSWORD)
                .waitingFor(Wait.forListeningPort().withStartupTimeout(STARTUP_TIMEOUT));
        return container;
    }

    /** Waits until the configured database accepts authenticated JDBC queries. */
    public static void awaitJdbcReady(PostgreSQLContainer container) {
        ensureDriverPresent(container);
        await()
                .alias("PostgreSQL JDBC readiness")
                .pollDelay(Duration.ZERO)
                .pollInterval(JDBC_POLL_INTERVAL)
                .atMost(STARTUP_TIMEOUT)
                .until(() -> canQuery(container));
    }

    private static void ensureDriverPresent(PostgreSQLContainer container) {
        try {
            Class.forName(container.getDriverClassName());
        } catch (ClassNotFoundException missingDriver) {
            throw new IllegalStateException(
                    "PostgreSQL JDBC driver is missing from the integration-test runtime", missingDriver);
        }
    }

    private static boolean canQuery(PostgreSQLContainer container) {
        try (Connection connection = DriverManager.getConnection(
                        container.getJdbcUrl(), container.getUsername(), container.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT 1")) {
            return rows.next() && rows.getInt(1) == 1;
        } catch (SQLException notReadyYet) {
            return false;
        }
    }

    private static final class JdbcReadyPostgreSQLContainer extends PostgreSQLContainer {
        private JdbcReadyPostgreSQLContainer(String dockerImageName) {
            super(dockerImageName);
        }

        @Override
        public void start() {
            super.start();
            try {
                awaitJdbcReady(this);
            } catch (RuntimeException | Error failure) {
                stop();
                throw failure;
            }
        }
    }
}
