package io.signalharvester.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Protects cross-module RBAC for {@code SECURITY.AUTHORIZATION}, including VIEWER Results access and
 * the absence of implicit ADMIN-to-VIEWER role inheritance.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityAuthorizationIntegrationTest {
    private static final String ADMIN_USERNAME = "integration-admin";
    private static final String ADMIN_PASSWORD = "integration-admin-password";
    private static final String JWT_SECRET = "integration-jwt-secret-that-is-long-enough-for-hmac-sha256-123456";
    private static final String CSRF_SECRET = "integration-csrf-secret-that-is-long-enough-for-hmac-signing-123456";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("signalharvester")
            .withUsername("signalharvester")
            .withPassword("signalharvester");

    private EmbeddedServer server;

    @BeforeAll
    void startServer() throws Exception {
        resetDatabase();
        server = ApplicationContext.run(EmbeddedServer.class, serverProperties(), "test", "security");
    }

    @AfterAll
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    /** Allow VIEWER Results REST/SSE while denying administrative and diagnostic APIs. */
    @Test
    void shouldAuthorizeViewerResultsBoundaries() throws Exception {
        Client admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String viewerName = "viewer-" + UUID.randomUUID().toString().substring(0, 8);
        assertEquals(201, admin.send("POST", "/api/v1/admin/users", """
                {
                  "username": "%s",
                  "password": "viewer-password-for-integration",
                  "identityType": "HUMAN",
                  "enabled": true,
                  "roles": ["VIEWER"]
                }
                """.formatted(viewerName), true).statusCode());

        Client viewer = login(viewerName, "viewer-password-for-integration");
        assertEquals(200, viewer.send("GET", "/api/v1/results?limit=1", null, false).statusCode());
        assertEquals(403, viewer.send("GET", "/api/v1/admin/collection-runs?limit=1", null, false).statusCode());
        assertEquals(403, viewer.send("GET", "/api/v1/events?limit=1", null, false).statusCode());

        HttpResponse<java.util.stream.Stream<String>> stream = viewer.sendLines("/api/v1/results/stream");
        assertEquals(200, stream.statusCode());
        try (java.util.stream.Stream<String> lines = stream.body()) {
            assertTrue(lines.limit(8).anyMatch(line -> line.contains("ready")));
        }

        Client anonymous = new Client(new CookieManager());
        assertEquals(401, anonymous.send("GET", "/api/v1/results?limit=1", null, false).statusCode());
    }

    /** Keep ADMIN and VIEWER independent when an administrator is not assigned viewer capability. */
    @Test
    void shouldNotInferViewerFromAdminRole() throws Exception {
        Client bootstrapAdmin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String adminName = "admin-only-" + UUID.randomUUID().toString().substring(0, 8);
        assertEquals(201, bootstrapAdmin.send("POST", "/api/v1/admin/users", """
                {
                  "username": "%s",
                  "password": "admin-password-for-integration",
                  "identityType": "HUMAN",
                  "enabled": true,
                  "roles": ["ADMIN"]
                }
                """.formatted(adminName), true).statusCode());

        Client adminOnly = login(adminName, "admin-password-for-integration");
        HttpResponse<String> adminBoundary = adminOnly.send("GET", "/api/v1/sources", null, false);
        assertEquals(200, adminBoundary.statusCode(),
                () -> "ADMIN boundary failed: " + adminBoundary.statusCode() + " " + adminBoundary.body());
        assertEquals(403, adminOnly.send("GET", "/api/v1/results?limit=1", null, false).statusCode());
    }

    private Client login(String username, String password) throws Exception {
        Client client = new Client(new CookieManager());
        HttpResponse<String> response = client.send("POST", "/api/v1/auth/login", """
                {"username":"%s","password":"%s"}
                """.formatted(username, password), false);
        assertEquals(200, response.statusCode(), () -> "Login failed: " + response.body());
        return client;
    }

    private static Map<String, Object> serverProperties() {
        return Map.ofEntries(
                Map.entry("micronaut.server.port", -1),
                Map.entry("datasources.default.url", POSTGRES.getJdbcUrl()),
                Map.entry("datasources.default.username", POSTGRES.getUsername()),
                Map.entry("datasources.default.password", POSTGRES.getPassword()),
                Map.entry("datasources.default.driver-class-name", "org.postgresql.Driver"),
                Map.entry("kafka.enabled", false),
                Map.entry("signalharvester.analysis.enabled", false),
                Map.entry("signalharvester.results.enabled", false),
                Map.entry("signalharvester.event-observation.enabled", false),
                Map.entry("signalharvester.collection.scheduler.enabled", false),
                Map.entry("signalharvester.analysis.outbox.enabled", false),
                Map.entry("micronaut.security.token.jwt.signatures.secret.generator.secret", JWT_SECRET),
                Map.entry("micronaut.security.csrf.signature-key", CSRF_SECRET),
                Map.entry("signalharvester.security.bootstrap.username", ADMIN_USERNAME),
                Map.entry("signalharvester.security.bootstrap.password", ADMIN_PASSWORD),
                Map.entry("signalharvester.security.password-hashing.iterations", 10_000),
                Map.entry("micronaut.security.token.cookie.cookie-secure", false),
                Map.entry("micronaut.security.csrf.cookie-secure", false));
    }

    private static void resetDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS configuration CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS collection CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS analysis CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS results CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS event_observation CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS security CASCADE");
            statement.execute("DROP TABLE IF EXISTS flyway_schema_history");
        }
    }

    private final class Client {
        private final CookieManager cookies;
        private final HttpClient client;

        private Client(CookieManager cookies) {
            this.cookies = cookies;
            this.client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        }

        private HttpResponse<String> send(String method, String path, String body, boolean includeCsrf) throws Exception {
            HttpRequest.Builder request = request(method, path, body, includeCsrf);
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<java.util.stream.Stream<String>> sendLines(String path) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(server.getURI().resolve(path))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofLines());
        }

        private HttpRequest.Builder request(String method, String path, String body, boolean includeCsrf) {
            HttpRequest.Builder request = HttpRequest.newBuilder(server.getURI().resolve(path)).timeout(HTTP_TIMEOUT);
            if (includeCsrf) {
                request.header("X-CSRF-TOKEN", cookie("XSRF-TOKEN").getValue());
            }
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json");
                request.method(method, HttpRequest.BodyPublishers.ofString(body));
            }
            return request;
        }

        private HttpCookie cookie(String name) {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing cookie " + name));
        }
    }
}
