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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
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

/**
 * Verifies monitoring-profile REST CRUD, source membership, validation, and PostgreSQL durability.
 *
 * <p>Features: {@code CONFIGURATION.MONITORING_PROFILES}, {@code CONTRACTS.HTTP}.</p>
 */
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
                  "criteria": {"keywords": "java,spring"},
                  "analysisSettings": {"keywords": ["Java", "Kafka", "JAVA"], "minimumMatches": 2}
                }
                """.formatted(firstSource, secondSource));
        assertEquals(201, created.statusCode());
        UUID profileId = extractId(created.body());
        assertTrue(created.body().contains("java,spring"));
        assertTrue(created.body().contains("\"analysisSettings\":{\"keywords\":[\"java\",\"kafka\"],\"minimumMatches\":2}"));
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
        assertTrue(updated.body().contains("\"analysisSettings\":{\"keywords\":[\"java\",\"kafka\"],\"minimumMatches\":2}"));

        assertEquals(200, send("GET", "/api/v1/monitoring-profiles", null).statusCode());
        assertEquals(204, send("DELETE", "/api/v1/monitoring-profiles/" + profileId, null).statusCode());
        assertEquals(404, send("GET", "/api/v1/monitoring-profiles/" + profileId, null).statusCode());
    }

    /** Materialize and persist compatibility defaults when an older create request omits Analysis settings. */
    @Test
    void shouldPersistCompatibilityDefaultsWhenCreateOmitsAnalysisSettings() throws Exception {
        UUID sourceId = createSource("Compatibility source", "https://example.test/compatibility");

        HttpResponse<String> created = send("POST", "/api/v1/monitoring-profiles", """
                {
                  "name": "Compatibility profile",
                  "informationCategory": "TOPIC",
                  "collectionIntervalMinutes": 20,
                  "sourceIds": ["%s"]
                }
                """.formatted(sourceId));

        assertEquals(201, created.statusCode());
        UUID profileId = extractId(created.body());
        assertTrue(created.body().contains("\"keywords\":[\"legacy-default\",\"fallback\"]"));
        assertTrue(created.body().contains("\"minimumMatches\":2"));
        assertPersistedAnalysisSettings(profileId, 2, 2);
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

        UUID sourceId = createSource("Valid source", "https://example.test/valid");
        HttpResponse<String> invalidAnalysis = send("POST", "/api/v1/monitoring-profiles", """
                {
                  "name": "Invalid analysis",
                  "informationCategory": "JOB",
                  "collectionIntervalMinutes": 15,
                  "sourceIds": ["%s"],
                  "analysisSettings": {"keywords": ["java", "JAVA"], "minimumMatches": 2}
                }
                """.formatted(sourceId));
        assertEquals(400, invalidAnalysis.statusCode());
    }

    /** Resolve pre-migration profiles from compatibility defaults and persist them on the next replacement. */
    @Test
    void shouldMigrateLegacyProfileSettingsOnUpdate() throws Exception {
        UUID sourceId = createSource("Legacy source", "https://example.test/legacy");
        UUID profileId = insertLegacyProfile(sourceId);

        HttpResponse<String> legacy = send("GET", "/api/v1/monitoring-profiles/" + profileId, null);
        assertEquals(200, legacy.statusCode());
        assertTrue(legacy.body().contains("\"keywords\":[\"legacy-default\",\"fallback\"]"));
        assertTrue(legacy.body().contains("\"minimumMatches\":2"));

        HttpResponse<String> updated = send("PUT", "/api/v1/monitoring-profiles/" + profileId, """
                {
                  "name": "Legacy profile",
                  "informationCategory": "TOPIC",
                  "enabled": false,
                  "collectionIntervalMinutes": 20,
                  "sourceIds": ["%s"]
                }
                """.formatted(sourceId));
        assertEquals(200, updated.statusCode());
        assertPersistedAnalysisSettings(profileId, 2, 2);
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

    private static UUID insertLegacyProfile(UUID sourceId) throws Exception {
        UUID profileId = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement profile = connection.prepareStatement(
                        "INSERT INTO configuration.monitoring_profiles "
                                + "(id, name, information_category, enabled, collection_interval_minutes, analysis_minimum_matches) "
                                + "VALUES (?, ?, ?, ?, ?, NULL)");
                PreparedStatement membership = connection.prepareStatement(
                        "INSERT INTO configuration.monitoring_profile_sources (profile_id, source_id, source_ordinal) "
                                + "VALUES (?, ?, 0)")) {
            profile.setObject(1, profileId);
            profile.setString(2, "Legacy profile");
            profile.setString(3, "TOPIC");
            profile.setBoolean(4, true);
            profile.setInt(5, 20);
            profile.executeUpdate();
            membership.setObject(1, profileId);
            membership.setObject(2, sourceId);
            membership.executeUpdate();
        }
        return profileId;
    }

    private static void assertPersistedAnalysisSettings(UUID profileId, int expectedMinimumMatches, int expectedKeywords)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT p.analysis_minimum_matches,
                               (SELECT COUNT(*) FROM configuration.monitoring_profile_analysis_keywords k
                                 WHERE k.profile_id = p.id) AS keyword_count
                          FROM configuration.monitoring_profiles p
                         WHERE p.id = ?
                        """)) {
            statement.setObject(1, profileId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(expectedMinimumMatches, rows.getInt("analysis_minimum_matches"));
                assertEquals(expectedKeywords, rows.getInt("keyword_count"));
            }
        }
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
                Map.entry("flyway.datasources.default.locations[0]", "classpath:db/migration/configuration"),
                Map.entry("signalharvester.analysis.keyword-rules.keywords", List.of("legacy-default", "fallback")),
                Map.entry("signalharvester.analysis.keyword-rules.minimum-matches", 2));
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
