package io.signalharvester.integration;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
 *
 * <p>Related feature: {@code RELIABILITY.DEAD_LETTER}.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityAuthorizationIntegrationTest {
    private static final String ADMIN_USERNAME = "integration-admin";
    private static final String ADMIN_PASSWORD = "integration-admin-password";
    private static final String JWT_SECRET = "integration-jwt-secret-that-is-long-enough-for-hmac-sha256-123456";
    private static final String CSRF_SECRET = "integration-csrf-secret-that-is-long-enough-for-hmac-signing-123456";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern ID_PATTERN = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

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
        HttpResponse<String> createdViewer = admin.send("POST", "/api/v1/admin/users", """
                {
                  "username": "%s",
                  "password": "viewer-password-for-integration",
                  "identityType": "HUMAN",
                  "enabled": true,
                  "roles": ["VIEWER"]
                }
                """.formatted(viewerName), true);
        assertEquals(201, createdViewer.statusCode(),
                () -> "VIEWER creation failed: " + createdViewer.statusCode() + " " + createdViewer.body());

        Client viewer = login(viewerName, "viewer-password-for-integration");
        assertEquals(200, viewer.send("GET", "/api/v1/results?limit=1", null, false).statusCode());
        assertEquals(404, viewer.send("GET",
                "/api/v1/results/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa?monitoringProfileId=missing",
                null,
                false).statusCode());
        assertEquals(403, viewer.send("GET", "/api/v1/admin/collection-runs?limit=1", null, false).statusCode());
        assertEquals(403, viewer.send("GET", "/api/v1/events?limit=1", null, false).statusCode());
        assertEquals(403, viewer.send("GET", "/api/v1/admin/analysis/dead-letters/0/0", null, false).statusCode());
        assertEquals(403, viewer.send("GET", "/api/v1/admin/results/dead-letters/0/0", null, false).statusCode());
        assertEquals(403, viewer.send(
                "GET", "/api/v1/admin/event-observation/dead-letters/0/0", null, false).statusCode());

        HttpResponse<java.util.stream.Stream<String>> stream = viewer.sendLines("/api/v1/results/stream");
        assertEquals(200, stream.statusCode());
        try (java.util.stream.Stream<String> lines = stream.body()) {
            assertTrue(lines.limit(8).anyMatch(line -> line.contains("ready")));
        }

        Client anonymous = new Client();
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

    /** Apply role removal only to newly issued JWTs while preserving already issued stateless credentials. */
    @Test
    void shouldApplyRoleChangesToNewJwtCredentials() throws Exception {
        Client bootstrapAdmin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String username = "role-change-" + UUID.randomUUID().toString().substring(0, 8);
        String password = "role-change-password-for-integration";
        HttpResponse<String> created = bootstrapAdmin.send("POST", "/api/v1/admin/users", """
                {
                  "username": "%s",
                  "password": "%s",
                  "identityType": "HUMAN",
                  "enabled": true,
                  "roles": ["VIEWER", "ADMIN"]
                }
                """.formatted(username, password), true);
        assertEquals(201, created.statusCode());
        UUID userId = extractId(created.body());

        Client oldCredential = login(username, password);
        assertEquals(200, oldCredential.send("GET", "/api/v1/results?limit=1", null, false).statusCode());

        HttpResponse<String> updated = bootstrapAdmin.send(
                "PUT",
                "/api/v1/admin/users/" + userId,
                "{\"enabled\":true,\"roles\":[\"ADMIN\"]}",
                true);
        assertEquals(200, updated.statusCode());

        assertEquals(200, oldCredential.send("GET", "/api/v1/results?limit=1", null, false).statusCode());
        Client newCredential = login(username, password);
        assertEquals(403, newCredential.send("GET", "/api/v1/results?limit=1", null, false).statusCode());
        assertEquals(200, newCredential.send("GET", "/api/v1/sources", null, false).statusCode());
    }

    /** Keep baseline USER and BOT identities authenticated without implicitly granting VIEWER or ADMIN access. */
    @Test
    void shouldKeepBaselineIdentitiesWithoutBusinessCapabilities() throws Exception {
        Client admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String humanName = "user-only-" + suffix;
        String botName = "bot-only-" + suffix;
        String humanPassword = "user-only-password-for-integration";
        String botPassword = "bot-only-password-for-integration";

        assertEquals(201, admin.send("POST", "/api/v1/admin/users", """
                {
                  "username": "%s",
                  "password": "%s",
                  "identityType": "HUMAN",
                  "enabled": true,
                  "roles": []
                }
                """.formatted(humanName, humanPassword), true).statusCode());
        assertEquals(201, admin.send("POST", "/api/v1/admin/users", """
                {
                  "username": "%s",
                  "password": "%s",
                  "identityType": "BOT",
                  "enabled": true,
                  "roles": []
                }
                """.formatted(botName, botPassword), true).statusCode());

        Client human = login(humanName, humanPassword);
        HttpResponse<String> humanPrincipal = human.send("GET", "/api/v1/auth/me", null, false);
        assertEquals(200, humanPrincipal.statusCode());
        assertTrue(humanPrincipal.body().contains("\"USER\""));
        assertEquals(403, human.send("GET", "/api/v1/results?limit=1", null, false).statusCode());
        assertEquals(403, human.send("GET", "/api/v1/sources", null, false).statusCode());

        Client bot = login(botName, botPassword);
        HttpResponse<String> botPrincipal = bot.send("GET", "/api/v1/auth/me", null, false);
        assertEquals(200, botPrincipal.statusCode());
        assertTrue(botPrincipal.body().contains("\"BOT\""));
        assertEquals(403, bot.send("GET", "/api/v1/results?limit=1", null, false).statusCode());
        assertEquals(403, bot.send("GET", "/api/v1/sources", null, false).statusCode());
    }

    /** Keep health and Prometheus operational boundaries anonymous when application security is enabled. */
    @Test
    void shouldKeepOperationalEndpointsAnonymous() throws Exception {
        Client anonymous = new Client();

        assertEquals(200, anonymous.send("GET", "/health", null, false).statusCode());
        assertEquals(200, anonymous.send("GET", "/health/liveness", null, false).statusCode());
        assertEquals(200, anonymous.send("GET", "/health/readiness", null, false).statusCode());
        assertEquals(200, anonymous.send("GET", "/prometheus", null, false).statusCode());
    }

    private Client login(String username, String password) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(server.getURI().resolve("/api/v1/auth/login"))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"username":"%s","password":"%s"}
                        """.formatted(username, password)))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(),
                () -> "Login failed: " + response.statusCode() + " " + response.body());
        return new Client(response);
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
        private final Map<String, BrowserCookie> cookies = new LinkedHashMap<>();
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        private Client() {
        }

        private Client(HttpResponse<?> loginResponse) {
            applySetCookieHeaders(loginResponse);
        }

        private HttpResponse<String> send(String method, String path, String body, boolean includeCsrf) throws Exception {
            HttpRequest.Builder request = request(path);
            if (includeCsrf) {
                request.header("X-CSRF-TOKEN", cookie("XSRF-TOKEN").value());
            }
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json");
                request.method(method, HttpRequest.BodyPublishers.ofString(body));
            }
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            applySetCookieHeaders(response);
            return response;
        }

        private HttpResponse<java.util.stream.Stream<String>> sendLines(String path) throws Exception {
            HttpRequest request = request(path).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.ofLines());
        }

        private HttpRequest.Builder request(String path) {
            HttpRequest.Builder request = HttpRequest.newBuilder(server.getURI().resolve(path)).timeout(HTTP_TIMEOUT);
            if (!cookies.isEmpty()) {
                request.header("Cookie", cookies.values().stream()
                        .map(cookie -> cookie.name() + "=" + cookie.value())
                        .collect(Collectors.joining("; ")));
            }
            return request;
        }

        private void applySetCookieHeaders(HttpResponse<?> response) {
            for (String header : response.headers().allValues("Set-Cookie")) {
                String[] segments = header.split(";");
                int separator = segments[0].indexOf('=');
                if (separator <= 0) {
                    throw new AssertionError("Invalid Set-Cookie header: " + header);
                }

                String name = segments[0].substring(0, separator).trim();
                String value = segments[0].substring(separator + 1).trim();
                boolean expired = false;
                for (int index = 1; index < segments.length; index++) {
                    String attribute = segments[index].trim();
                    if (attribute.regionMatches(true, 0, "Max-Age=", 0, "Max-Age=".length())) {
                        expired = "0".equals(attribute.substring("Max-Age=".length()).trim());
                    }
                }

                if (expired) {
                    cookies.remove(name);
                } else {
                    cookies.put(name, new BrowserCookie(name, value));
                }
            }
        }

        private BrowserCookie cookie(String name) {
            BrowserCookie cookie = cookies.get(name);
            if (cookie == null) {
                throw new AssertionError("Missing cookie " + name + "; available cookies=" + cookies.keySet());
            }
            return cookie;
        }
    }

    private record BrowserCookie(String name, String value) {
    }
}
