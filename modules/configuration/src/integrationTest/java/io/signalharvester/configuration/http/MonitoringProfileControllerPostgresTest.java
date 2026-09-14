package io.signalharvester.configuration.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Verifies monitoring-profile REST CRUD, source membership, validation, and PostgreSQL durability. */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MonitoringProfileControllerPostgresTest {
    private static final Pattern ID_PATTERN = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(15);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("signalharvester")
            .withUsername("signalharvester")
            .withPassword("signalharvester");

    private EmbeddedServer server;
    private HttpClient client;

    @BeforeAll
    void startServer() throws Exception {
        resetDatabase();
        server = ApplicationContext.run(EmbeddedServer.class, serverProperties(), "test");
        client = HttpClient.newBuilder().connectTimeout(HTTP_CONNECT_TIMEOUT).build();
    }

    @BeforeEach
    void resetData() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE configuration.monitoring_profile_criteria, "
                    + "configuration.monitoring_profile_sources, configuration.monitoring_profiles, "
                    + "configuration.source_settings, configuration.sources CASCADE");
        }
    }

    @AfterAll
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    /** Persist, replace, list, fetch, and delete a profile with explicit source membership. */
    @Test
    void shouldServeMonitoringProfileCrud() throws Exception {
        UUID firstSource = createSource("Jobs feed", "https://example.test/jobs");
        UUID secondSource = createSource("News feed", "https://example.test/news");

        HttpResponse<String> created = send("POST", "/api/v1/monitoring-profiles", """
                {
                  "name": "Java backend jobs",
                  "informationCategory": "JOB",
                  "enabled": true,
                  "collectionIntervalMinutes": 15,
                  "sourceIds": ["%s", "%s"],
                  "criteria": {"keywords": "java,spring"}
                }
                """.formatted(firstSource, secondSource));
        assertEquals(201, created.statusCode());
        UUID profileId = extractId(created.body());
        assertTrue(created.body().contains("java,spring"));
        assertEquals(409, send("DELETE", "/api/v1/sources/" + firstSource, null).statusCode());

        HttpResponse<String> fetched = send("GET", "/api/v1/monitoring-profiles/" + profileId, null);
        assertEquals(200, fetched.statusCode());
        assertTrue(fetched.body().indexOf(firstSource.toString()) < fetched.body().indexOf(secondSource.toString()));

        HttpResponse<String> updated = send("PUT", "/api/v1/monitoring-profiles/" + profileId, """
                {
                  "name": "Java jobs EU",
                  "informationCategory": "JOB",
                  "enabled": false,
                  "collectionIntervalMinutes": 30,
                  "sourceIds": ["%s"],
                  "criteria": {"keywords": "java", "region": "eu"}
                }
                """.formatted(secondSource));
        assertEquals(200, updated.statusCode());
        assertTrue(updated.body().contains("Java jobs EU"));
        assertTrue(updated.body().contains("\"collectionIntervalMinutes\":30"));

        assertEquals(200, send("GET", "/api/v1/monitoring-profiles", null).statusCode());
        assertEquals(204, send("DELETE", "/api/v1/monitoring-profiles/" + profileId, null).statusCode());
        assertEquals(404, send("GET", "/api/v1/monitoring-profiles/" + profileId, null).statusCode());
    }

    /** Reject unknown sources and invalid empty membership. */
    @Test
    void shouldRejectInvalidMonitoringProfile() throws Exception {
        UUID missingSource = UUID.fromString("00000000-0000-0000-0000-000000000099");
        HttpResponse<String> unknownSource = send("POST", "/api/v1/monitoring-profiles", """
                {
                  "name": "Invalid",
                  "informationCategory": "JOB",
                  "collectionIntervalMinutes": 15,
                  "sourceIds": ["%s"]
                }
                """.formatted(missingSource));
        assertEquals(400, unknownSource.statusCode());

        HttpResponse<String> emptySources = send("POST", "/api/v1/monitoring-profiles", """
                {
                  "name": "Invalid",
                  "informationCategory": "JOB",
                  "collectionIntervalMinutes": 15,
                  "sourceIds": []
                }
                """);
        assertEquals(400, emptySources.statusCode());
    }

    private UUID createSource(String name, String location) throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/sources", """
                {
                  "name": "%s",
                  "type": "RSS",
                  "location": "%s",
                  "enabled": true
                }
                """.formatted(name, location));
        assertEquals(201, response.statusCode());
        return extractId(response.body());
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(server.getURI().resolve(path)).timeout(HTTP_REQUEST_TIMEOUT);
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
            throw new AssertionError("Response did not contain an id: " + body);
        }
        return UUID.fromString(matcher.group(1));
    }

    private static Map<String, Object> serverProperties() {
        return Map.ofEntries(
                Map.entry("micronaut.server.port", -1),
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
            statement.execute("DROP TABLE IF EXISTS flyway_schema_history");
        }
    }
}
