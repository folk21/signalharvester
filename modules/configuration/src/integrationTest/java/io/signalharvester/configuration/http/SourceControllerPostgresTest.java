package io.signalharvester.configuration.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.persistence.JdbiSourceRepository;
import io.signalharvester.configuration.persistence.SourceRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies {@link SourceController} through an embedded HTTP server backed by real PostgreSQL, including CRUD,
 * validation, runtime defaults, supported source types, and blocking execution.
 *
 * <p>Related specification: {@code backend-configuration-persistence-rest}.</p>
 *
 * <p>Features: {@code CONFIGURATION.SOURCES}, {@code CONTRACTS.HTTP}.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SourceControllerPostgresTest {

    private static final String SPEC_NAME = "source-controller-postgres";
    private static final Pattern ID_PATTERN = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final String MISSING_SOURCE_ID = "00000000-0000-0000-0000-000000000001";
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(15);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("signalharvester")
            .withUsername("signalharvester")
            .withPassword("signalharvester");

    private ApplicationContext context;
    private EmbeddedServer server;
    private HttpClient client;

    @BeforeAll
    void startServer() throws Exception {
        resetDatabase();
        server = ApplicationContext.run(EmbeddedServer.class, serverProperties(), "test");
        context = server.getApplicationContext();
        client = HttpClient.newBuilder().connectTimeout(HTTP_CONNECT_TIMEOUT).build();
    }

    @BeforeEach
    void resetData() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE configuration.source_settings, configuration.sources CASCADE");
            statement.execute("TRUNCATE TABLE operations.change_journal, operations.health_snapshots");
        }
    }

    @AfterAll
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    /**
     * Serve CRUD through PostgreSQL on blocking virtual thread.
     */
    @Test
    void shouldServeCrudThroughPostgresOnBlockingVirtualThread() throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/sources", """
                {
                  "name": "Jobs API",
                  "type": "REST",
                  "location": "https://example.test/jobs",
                  "enabled": true,
                  "settings": {"query": "java backend"}
                }
                """);
        assertEquals(201, created.statusCode());
        UUID sourceId = extractId(created.body());
        String journalState = scalarString("""
                SELECT after_state::text
                FROM operations.change_journal
                WHERE category = 'SOURCE_CONFIGURATION'
                  AND target_id = '%s'
                ORDER BY changed_at ASC
                LIMIT 1
                """.formatted(sourceId));
        assertTrue(journalState.contains("settingKeys"));
        assertTrue(journalState.contains("query"));
        assertFalse(journalState.contains("java backend"));

        HttpResponse<String> listed = send("GET", "/api/v1/sources", null);
        assertEquals(200, listed.statusCode());
        assertTrue(listed.body().contains(sourceId.toString()));

        ObservingSourceRepository observingRepository = context.getBean(ObservingSourceRepository.class);
        Thread persistenceThread = observingRepository.lastThread();
        assertTrue(persistenceThread.isVirtual());
        assertFalse(persistenceThread.getName().contains("EventLoop"));

        HttpResponse<String> fetched = send("GET", "/api/v1/sources/" + sourceId, null);
        assertEquals(200, fetched.statusCode());
        assertTrue(fetched.body().contains("java backend"));

        HttpResponse<String> updated = send("PUT", "/api/v1/sources/" + sourceId, """
                {
                  "name": "Updated Jobs API",
                  "type": "REST",
                  "location": "https://example.test/v2/jobs",
                  "enabled": false,
                  "settings": {}
                }
                """);
        assertEquals(200, updated.statusCode());
        assertTrue(updated.body().contains("Updated Jobs API"));

        assertEquals(204, send("DELETE", "/api/v1/sources/" + sourceId, null).statusCode());
        assertEquals(404, send("GET", "/api/v1/sources/" + sourceId, null).statusCode());
    }

    /**
     * Reject invalid input and map missing sources.
     */
    @Test
    void shouldRejectInvalidInputAndMapMissingSources() throws Exception {
        HttpResponse<String> blankName = send("POST", "/api/v1/sources", """
                {
                  "name": "   ",
                  "type": "REST",
                  "location": "https://example.test/jobs",
                  "enabled": true
                }
                """);
        assertEquals(400, blankName.statusCode());

        HttpResponse<String> embeddedCredentials = send("POST", "/api/v1/sources", """
                {
                  "name": "Unsafe",
                  "type": "REST",
                  "location": "https://user:secret@example.test/jobs",
                  "enabled": true
                }
                """);
        assertEquals(400, embeddedCredentials.statusCode());

        HttpResponse<String> omittedSettings = send("POST", "/api/v1/sources", """
                {
                  "name": "RSS feed",
                  "type": "RSS",
                  "location": "https://example.test/feed",
                  "enabled": false
                }
                """);
        assertEquals(201, omittedSettings.statusCode());

        assertEquals(404, send(
                "GET",
                "/api/v1/sources/" + MISSING_SOURCE_ID,
                null).statusCode());
    }


    /**
     * Reject missing and malformed source fields.
     */
    @Test
    void shouldRejectMissingAndMalformedSourceFields() throws Exception {
        HttpResponse<String> missingLocation = send("POST", "/api/v1/sources", """
                {
                  "name": "Missing Location",
                  "type": "REST"
                }
                """);
        assertEquals(400, missingLocation.statusCode());

        HttpResponse<String> invalidScheme = send("POST", "/api/v1/sources", """
                {
                  "name": "Invalid Scheme",
                  "type": "REST",
                  "location": "ftp://example.test/file",
                  "enabled": true
                }
                """);
        assertEquals(400, invalidScheme.statusCode());

        HttpResponse<String> invalidType = send("POST", "/api/v1/sources", """
                {
                  "name": "Invalid Type",
                  "type": "HTTP",
                  "location": "https://example.test/api"
                }
                """);
        assertEquals(400, invalidType.statusCode());
    }

    /**
     * Persist each supported source type through the REST boundary.
     */
    @ParameterizedTest(name = "persists {0} source")
    @ValueSource(strings = {"REST", "RSS", "HTML"})
    void shouldPersistSupportedSourceType(String type) throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/sources", """
                {
                  "name": "%s Source",
                  "type": "%s",
                  "location": "https://example.test/%s"
                }
                """.formatted(type, type, type.toLowerCase()));

        assertEquals(201, created.statusCode());
        assertTrue(created.body().contains("\"enabled\":false"));
        assertTrue(created.body().contains("\"settings\":{}"));

        HttpResponse<String> listed = send("GET", "/api/v1/sources", null);
        assertEquals(200, listed.statusCode());
        assertTrue(listed.body().contains(type));
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(server.getURI().resolve(path))
                .timeout(HTTP_REQUEST_TIMEOUT);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static UUID extractId(String body) {
        Matcher matcher = ID_PATTERN.matcher(body);
        if (!matcher.find()) {
            throw new AssertionError("Response did not contain a source id: " + body);
        }
        return UUID.fromString(matcher.group(1));
    }

    private static String scalarString(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static Map<String, Object> serverProperties() {
        return Map.ofEntries(
                Map.entry("spec.name", SPEC_NAME),
                Map.entry("micronaut.server.port", -1),
                Map.entry("micronaut.executors.blocking.virtual", true),
                Map.entry("datasources.default.url", POSTGRES.getJdbcUrl()),
                Map.entry("datasources.default.username", POSTGRES.getUsername()),
                Map.entry("datasources.default.password", POSTGRES.getPassword()),
                Map.entry("datasources.default.driver-class-name", "org.postgresql.Driver"),
                Map.entry("flyway.datasources.default.enabled", true),
                Map.entry("flyway.datasources.default.locations[0]", "classpath:db/migration/configuration"),
                Map.entry("flyway.datasources.default.locations[1]", "classpath:db/migration/operations"));
    }

    private static void resetDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS configuration CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS operations CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }

    @Singleton
    @Replaces(JdbiSourceRepository.class)
    @Requires(property = "spec.name", value = SPEC_NAME)
    static final class ObservingSourceRepository implements SourceRepository {

        private final JdbiSourceRepository delegate;
        private final AtomicReference<Thread> lastThread = new AtomicReference<>();

        ObservingSourceRepository(@Named("default") Jdbi jdbi) {
            this.delegate = new JdbiSourceRepository(jdbi);
        }

        Thread lastThread() {
            Thread thread = lastThread.get();
            if (thread == null) {
                throw new AssertionError("No persistence call was observed");
            }
            return thread;
        }

        @Override
        public List<ConfiguredSource> findAll() {
            observe();
            return delegate.findAll();
        }

        @Override
        public List<ConfiguredSource> findEnabled() {
            observe();
            return delegate.findEnabled();
        }

        @Override
        public Optional<ConfiguredSource> findById(SourceId sourceId) {
            observe();
            return delegate.findById(sourceId);
        }

        @Override
        public void insert(ConfiguredSource source) {
            observe();
            delegate.insert(source);
        }

        @Override
        public boolean update(ConfiguredSource source) {
            observe();
            return delegate.update(source);
        }

        @Override
        public boolean delete(SourceId sourceId) {
            observe();
            return delegate.delete(sourceId);
        }

        private void observe() {
            lastThread.set(Thread.currentThread());
        }
    }
}
