package io.signalharvester.security.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micronaut.runtime.server.EmbeddedServer;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies PostgreSQL identities, cookie JWT login, CSRF, and RBAC for features
 * {@code SECURITY.IDENTITY_ROLES}, {@code SECURITY.AUTHENTICATION}, and {@code SECURITY.AUTHORIZATION}.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityHttpPostgresIntegrationTest {
    private static final String ADMIN_USERNAME = "bootstrap-admin";
    private static final String ADMIN_PASSWORD = "bootstrap-password-for-tests";
    private static final String JWT_SECRET = "test-jwt-secret-that-is-long-enough-for-hmac-sha256-signing-123456789";
    private static final String CSRF_SECRET = "test-csrf-secret-that-is-long-enough-for-signed-double-submit-123456";
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
        server = ApplicationContext.run(EmbeddedServer.class, serverProperties(), "test");
    }

    @AfterAll
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    /** Bootstrap the first administrator and authenticate without a database-backed session. */
    @Test
    void shouldBootstrapAdminAndAuthenticateWithHttpOnlyJwtCookie() throws Exception {
        AuthenticatedClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        HttpResponse<String> current = admin.send("GET", "/api/v1/auth/me", null, false);
        assertEquals(200, current.statusCode());
        assertTrue(current.body().contains("\"username\":\"" + ADMIN_USERNAME + "\""));
        assertTrue(current.body().contains("\"ADMIN\""));
        assertTrue(current.body().contains("\"VIEWER\""));
        assertTrue(admin.hasCookie("SIGNALHARVESTER_AUTH"));
        assertTrue(admin.cookie("SIGNALHARVESTER_AUTH").isHttpOnly());
        assertTrue(admin.hasCookie("XSRF-TOKEN"));
        assertFalse(admin.cookie("XSRF-TOKEN").isHttpOnly());

        String storedHash = scalarString("SELECT password_hash FROM security.users WHERE username = '" + ADMIN_USERNAME + "'");
        assertNotEquals(ADMIN_PASSWORD, storedHash);
        assertTrue(storedHash.startsWith("pbkdf2-sha256$"));
        assertEquals(0, scalarLong("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'security'
                  AND table_name LIKE '%session%'
                """));

        HttpResponse<String> logout = admin.send("POST", "/api/v1/auth/logout", "{}", true);
        assertEquals(200, logout.statusCode(), () -> "Logout failed: " + logout.statusCode() + " " + logout.body());
        assertEquals(401, admin.send("GET", "/api/v1/auth/me", null, false).statusCode());
    }

    /** Reject signed tokens whose signature, issuer, audience, expiry, subject, or roles violate the JWT contract. */
    @Test
    void shouldValidateJwtSignatureAndClaims() throws Exception {
        String subject = "00000000-0000-0000-0000-000000000321";
        String valid = signedToken(subject, List.of("USER", "VIEWER"), "signalharvester", "signalharvester-api",
                Instant.now().plusSeconds(60), JWT_SECRET);
        assertEquals(200, bearerRequest(valid).statusCode());

        assertEquals(401, bearerRequest(signedToken(subject, List.of("USER"), "wrong-issuer",
                "signalharvester-api", Instant.now().plusSeconds(60), JWT_SECRET)).statusCode());
        assertEquals(401, bearerRequest(signedToken(subject, List.of("USER"), "signalharvester",
                "wrong-audience", Instant.now().plusSeconds(60), JWT_SECRET)).statusCode());
        assertEquals(401, bearerRequest(signedToken(subject, List.of("USER"), "signalharvester",
                "signalharvester-api", Instant.now().minusSeconds(60), JWT_SECRET)).statusCode());
        assertEquals(401, bearerRequest(signedToken("not-a-uuid", List.of("USER"), "signalharvester",
                "signalharvester-api", Instant.now().plusSeconds(60), JWT_SECRET)).statusCode());
        assertEquals(401, bearerRequest(signedToken(subject, List.of("SUPERADMIN"), "signalharvester",
                "signalharvester-api", Instant.now().plusSeconds(60), JWT_SECRET)).statusCode());
        assertEquals(401, bearerRequest(signedToken(subject, List.of("USER"), "signalharvester",
                "signalharvester-api", Instant.now().plusSeconds(60), JWT_SECRET + "-wrong")).statusCode());
    }

    /** Apply baseline USER/BOT roles while keeping VIEWER and ADMIN assignments explicit. */
    @Test
    void shouldPersistExplicitAdditiveRoleSets() throws Exception {
        AuthenticatedClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        HttpResponse<String> human = admin.send("POST", "/api/v1/admin/users", """
                {
                  "username": "viewer-%s",
                  "password": "viewer-password-for-tests",
                  "identityType": "HUMAN",
                  "enabled": true,
                  "roles": ["VIEWER"]
                }
                """.formatted(suffix), true);
        assertEquals(201, human.statusCode());
        assertTrue(human.body().contains("\"USER\""));
        assertTrue(human.body().contains("\"VIEWER\""));
        assertFalse(human.body().contains("\"ADMIN\""));
        assertFalse(human.body().contains("password"));

        HttpResponse<String> bot = admin.send("POST", "/api/v1/admin/users", """
                {
                  "username": "bot-%s",
                  "password": "bot-password-for-tests",
                  "identityType": "BOT",
                  "enabled": true,
                  "roles": []
                }
                """.formatted(suffix), true);
        assertEquals(201, bot.statusCode());
        assertTrue(bot.body().contains("\"BOT\""));
        assertFalse(bot.body().contains("\"USER\""));
    }

    /** Require CSRF proof for cookie-authenticated mutations and enforce ADMIN independently of UI routing. */
    @Test
    void shouldRequireCsrfAndRejectViewerFromAdminApi() throws Exception {
        AuthenticatedClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String username = "viewer-rbac-" + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {
                  "username": "%s",
                  "password": "viewer-password-for-tests",
                  "identityType": "HUMAN",
                  "enabled": true,
                  "roles": ["VIEWER"]
                }
                """.formatted(username);

        assertEquals(403, admin.send("POST", "/api/v1/admin/users", body, false).statusCode());

        HttpResponse<String> created = admin.send("POST", "/api/v1/admin/users", body, true);
        assertEquals(201, created.statusCode());

        AuthenticatedClient viewer = login(username, "viewer-password-for-tests");
        assertEquals(200, viewer.send("GET", "/api/v1/auth/me", null, false).statusCode());
        assertEquals(403, viewer.send("GET", "/api/v1/admin/users", null, false).statusCode());
    }

    /** Allow only the explicitly configured credentialed browser origin for cross-origin requests. */
    @Test
    void shouldExposeExplicitCredentialedCorsPolicy() throws Exception {
        HttpRequest allowed = HttpRequest.newBuilder(server.getURI().resolve("/api/v1/auth/login"))
                .timeout(HTTP_TIMEOUT)
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(allowed, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("http://localhost:5173", response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals("true", response.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
        assertNotEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
    }

    /** Disable future authentication while already issued short-lived JWTs remain stateless until expiry. */
    @Test
    void shouldPreventFutureLoginAfterAccountDisablement() throws Exception {
        AuthenticatedClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String username = "disable-me-" + UUID.randomUUID().toString().substring(0, 8);
        String password = "viewer-password-for-tests";
        HttpResponse<String> created = admin.send("POST", "/api/v1/admin/users", """
                {
                  "username": "%s",
                  "password": "%s",
                  "identityType": "HUMAN",
                  "enabled": true,
                  "roles": ["VIEWER"]
                }
                """.formatted(username, password), true);
        UUID userId = extractId(created.body());
        AuthenticatedClient viewer = login(username, password);

        HttpResponse<String> disabled = admin.send("PUT", "/api/v1/admin/users/" + userId, """
                {"enabled": false, "roles": ["VIEWER"]}
                """, true);
        assertEquals(200, disabled.statusCode(), () -> "Disable failed: " + disabled.statusCode() + " " + disabled.body());
        assertTrue(disabled.body().contains("\"enabled\":false"));

        assertEquals(200, viewer.send("GET", "/api/v1/auth/me", null, false).statusCode());
        assertEquals(401, loginResponse(username, password, new CookieManager()).statusCode());
    }

    private HttpResponse<String> bearerRequest(String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(server.getURI().resolve("/api/v1/auth/me"))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String signedToken(
            String subject,
            List<String> roles,
            String issuer,
            String audience,
            Instant expiration,
            String secret) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuer)
                .audience(audience)
                .expirationTime(Date.from(expiration))
                .issueTime(new Date())
                .claim("roles", roles)
                .build();
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        token.sign(new MACSigner(secret));
        return token.serialize();
    }

    private AuthenticatedClient login(String username, String password) throws Exception {
        CookieManager cookies = new CookieManager();
        HttpResponse<String> response = loginResponse(username, password, cookies);
        assertEquals(200, response.statusCode(), () -> "Login failed: " + response.body());
        return new AuthenticatedClient(cookies);
    }

    private HttpResponse<String> loginResponse(String username, String password, CookieManager cookies) throws Exception {
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(server.getURI().resolve("/api/v1/auth/login"))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"username":"%s","password":"%s"}
                        """.formatted(username, password)))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static UUID extractId(String body) {
        Matcher matcher = ID_PATTERN.matcher(body);
        if (!matcher.find()) {
            throw new AssertionError("Response did not contain an id: " + body);
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

    private static long scalarLong(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static Map<String, Object> serverProperties() {
        return Map.ofEntries(
                Map.entry("micronaut.application.name", "signalharvester"),
                Map.entry("micronaut.server.port", -1),
                Map.entry("datasources.default.url", POSTGRES.getJdbcUrl()),
                Map.entry("datasources.default.username", POSTGRES.getUsername()),
                Map.entry("datasources.default.password", POSTGRES.getPassword()),
                Map.entry("datasources.default.driver-class-name", "org.postgresql.Driver"),
                Map.entry("flyway.datasources.default.enabled", true),
                Map.entry("flyway.datasources.default.locations[0]", "classpath:db/migration/security"),
                Map.entry("micronaut.security.enabled", true),
                Map.entry("micronaut.security.authentication", "cookie"),
                Map.entry("micronaut.security.redirect.enabled", false),
                Map.entry("micronaut.security.reject-not-found", false),
                Map.entry("micronaut.security.basic-auth.enabled", false),
                Map.entry("micronaut.security.endpoints.login.enabled", true),
                Map.entry("micronaut.security.endpoints.login.path", "/api/v1/auth/login"),
                Map.entry("micronaut.security.endpoints.logout.enabled", false),
                Map.entry("micronaut.security.token.generator.access-token.expiration", 900),
                Map.entry("micronaut.security.token.jwt.signatures.secret.generator.secret", JWT_SECRET),
                Map.entry("micronaut.security.token.jwt.signatures.secret.generator.jws-algorithm", "HS256"),
                Map.entry("micronaut.security.token.jwt.claims-validators.issuer", "signalharvester"),
                Map.entry("micronaut.security.token.jwt.claims-validators.audience", "signalharvester-api"),
                Map.entry("micronaut.security.token.cookie.enabled", true),
                Map.entry("micronaut.security.token.cookie.cookie-name", "SIGNALHARVESTER_AUTH"),
                Map.entry("micronaut.security.token.cookie.cookie-http-only", true),
                Map.entry("micronaut.security.token.cookie.cookie-secure", false),
                Map.entry("micronaut.security.token.cookie.cookie-same-site", "Strict"),
                Map.entry("micronaut.security.token.cookie.cookie-path", "/"),
                Map.entry("micronaut.security.token.bearer.enabled", true),
                Map.entry("signalharvester.security.jwt.audience", "signalharvester-api"),
                Map.entry("micronaut.security.csrf.enabled", true),
                Map.entry("micronaut.security.csrf.signature-key", CSRF_SECRET),
                Map.entry("micronaut.security.csrf.cookie-name", "XSRF-TOKEN"),
                Map.entry("micronaut.security.csrf.cookie-http-only", false),
                Map.entry("micronaut.security.csrf.cookie-secure", false),
                Map.entry("micronaut.security.csrf.filter.regex-pattern", "^(?!/api/v1/auth/login$).*$"),
                Map.entry("micronaut.security.csrf.filter.content-types[0]", "application/json"),
                Map.entry("signalharvester.security.bootstrap.username", ADMIN_USERNAME),
                Map.entry("signalharvester.security.bootstrap.password", ADMIN_PASSWORD),
                Map.entry("signalharvester.security.password-hashing.iterations", 10_000),
                Map.entry("micronaut.server.cors.enabled", true),
                Map.entry("micronaut.server.cors.configurations.web.allowed-origins[0]", "http://localhost:5173"),
                Map.entry("micronaut.server.cors.configurations.web.allowed-methods[0]", "GET"),
                Map.entry("micronaut.server.cors.configurations.web.allowed-methods[1]", "POST"),
                Map.entry("micronaut.server.cors.configurations.web.allowed-methods[2]", "PUT"),
                Map.entry("micronaut.server.cors.configurations.web.allowed-methods[3]", "OPTIONS"),
                Map.entry("micronaut.server.cors.configurations.web.allowed-headers[0]", "Content-Type"),
                Map.entry("micronaut.server.cors.configurations.web.allowed-headers[1]", "X-CSRF-TOKEN"),
                Map.entry("micronaut.server.cors.configurations.web.allowed-headers[2]", "Authorization"),
                Map.entry("micronaut.server.cors.configurations.web.allow-credentials", true),
                Map.entry("micronaut.security.intercept-url-map[0].pattern", "/api/v1/auth/login"),
                Map.entry("micronaut.security.intercept-url-map[0].access[0]", "isAnonymous()"),
                Map.entry("micronaut.security.intercept-url-map[1].pattern", "/api/v1/auth/logout"),
                Map.entry("micronaut.security.intercept-url-map[1].access[0]", "isAuthenticated()"),
                Map.entry("micronaut.security.intercept-url-map[2].pattern", "/api/v1/auth/me"),
                Map.entry("micronaut.security.intercept-url-map[2].access[0]", "isAuthenticated()"),
                Map.entry("micronaut.security.intercept-url-map[3].pattern", "/api/v1/admin/users"),
                Map.entry("micronaut.security.intercept-url-map[3].access[0]", "ADMIN"),
                Map.entry("micronaut.security.intercept-url-map[4].pattern", "/api/v1/admin/users/**"),
                Map.entry("micronaut.security.intercept-url-map[4].access[0]", "ADMIN"),
                Map.entry("micronaut.security.intercept-url-map[5].pattern", "/api/v1/**"),
                Map.entry("micronaut.security.intercept-url-map[5].access[0]", "ADMIN"));
    }

    private static void resetDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS security CASCADE");
            statement.execute("DROP TABLE IF EXISTS flyway_schema_history");
        }
    }

    private final class AuthenticatedClient {
        private final CookieManager cookies;
        private final HttpClient client;

        private AuthenticatedClient(CookieManager cookies) {
            this.cookies = cookies;
            this.client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        }

        private HttpResponse<String> send(String method, String path, String body, boolean includeCsrf) throws Exception {
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
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        private boolean hasCookie(String name) {
            return cookies.getCookieStore().getCookies().stream().anyMatch(cookie -> cookie.getName().equals(name));
        }

        private HttpCookie cookie(String name) {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing cookie " + name));
        }
    }
}
