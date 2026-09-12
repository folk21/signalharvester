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
import io.signalharvester.configuration.persistence.JdbcSourceRepository;
import io.signalharvester.configuration.persistence.SourceRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class SourceControllerPostgresTest {

    private static final String SPEC_NAME = "source-controller-postgres";
    private static final Pattern ID_PATTERN = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("signalharvester")
            .withUsername("signalharvester")
            .withPassword("signalharvester");

    private ApplicationContext context;
    private EmbeddedServer server;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        server = ApplicationContext.run(EmbeddedServer.class, serverProperties(), "test");
        context = server.getApplicationContext();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

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
                "/api/v1/sources/00000000-0000-0000-0000-000000000001",
                null).statusCode());
    }


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

    @Test
    void shouldPersistAllSupportedSourceTypes() throws Exception {
        for (String type : List.of("REST", "RSS", "HTML")) {
            HttpResponse<String> created = send("POST", "/api/v1/sources", """
                    {
                      "name": "%s Source",
                      "type": "%s",
                      "location": "https://example.test/%s"
                    }
                    """.formatted(type, type, type.toLowerCase()));

            assertEquals(201, created.statusCode());
        }

        HttpResponse<String> listed = send("GET", "/api/v1/sources", null);
        assertEquals(200, listed.statusCode());
        assertTrue(listed.body().contains("REST"));
        assertTrue(listed.body().contains("RSS"));
        assertTrue(listed.body().contains("HTML"));
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(server.getURI().resolve(path));
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
                Map.entry("flyway.datasources.default.locations[0]", "classpath:db/migration/configuration"));
    }

    private static void resetDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS configuration CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }

    @Singleton
    @Replaces(JdbcSourceRepository.class)
    @Requires(property = "spec.name", value = SPEC_NAME)
    static final class ObservingSourceRepository implements SourceRepository {

        private final JdbcSourceRepository delegate;
        private final AtomicReference<Thread> lastThread = new AtomicReference<>();

        ObservingSourceRepository(@Named("default") DataSource dataSource) {
            this.delegate = new JdbcSourceRepository(dataSource);
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
