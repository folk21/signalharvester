package io.signalharvester.security.http;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micronaut.runtime.server.EmbeddedServer;
import io.signalharvester.security.application.CreateUserCommand;
import io.signalharvester.security.application.InvalidUserConfigurationException;
import io.signalharvester.security.application.UpdateUserCommand;
import io.signalharvester.security.application.UserAccountOperations;
import io.signalharvester.security.model.IdentityType;
import io.signalharvester.security.model.UserId;
import io.signalharvester.security.model.UserRole;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies PostgreSQL identities, cookie JWT login, CSRF, and RBAC for features
 * {@code SECURITY.IDENTITY_ROLES}, {@code SECURITY.AUTHENTICATION}, and {@code SECURITY.AUTHORIZATION}.
 */
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
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
    private HttpClient httpClient;

    /** Start each scenario with a fresh Micronaut runtime and freshly migrated security schema. */
    @BeforeEach
    void startServer() throws Exception {
        resetDatabase();
        server = ApplicationContext.run(EmbeddedServer.class, serverProperties(), "test");
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** Close per-scenario client and runtime resources before the next database reset. */
    @AfterEach
    void stopServer() {
        if (httpClient != null) {
            httpClient.close();
            httpClient = null;
        }
        if (server != null) {
            server.close();
            server = null;
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
        assertTrue(admin.cookie("SIGNALHARVESTER_AUTH").httpOnly());
        assertTrue(admin.hasCookie("XSRF-TOKEN"));
        assertFalse(admin.cookie("XSRF-TOKEN").httpOnly());

        SignedJWT issuedToken = SignedJWT.parse(admin.cookie("SIGNALHARVESTER_AUTH").value());
        assertTrue(issuedToken.verify(new MACVerifier(JWT_SECRET)));
        JWTClaimsSet issuedClaims = issuedToken.getJWTClaimsSet();
        assertEquals(scalarString("SELECT id::text FROM security.users WHERE username = '" + ADMIN_USERNAME + "'"),
                issuedClaims.getSubject());
        assertEquals("signalharvester", issuedClaims.getIssuer());
        assertEquals(List.of("signalharvester-api"), issuedClaims.getAudience());
        assertEquals(new HashSet<>(List.of("ADMIN", "USER", "VIEWER")),
                new HashSet<>(issuedClaims.getStringListClaim("roles")));
        assertTrue(issuedClaims.getExpirationTime().toInstant().isAfter(Instant.now()));

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
        assertEquals(200, logout.statusCode(),
                () -> "Logout failed: " + logout.statusCode() + " " + logout.body()
                        + " set-cookie=" + logout.headers().allValues("Set-Cookie"));
        assertFalse(admin.hasCookie("SIGNALHARVESTER_AUTH"));
        assertFalse(admin.hasCookie("XSRF-TOKEN"));
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

    /** Reject invalid application commands before hashing, lookup, or persistence. */
    @Test
    void shouldValidateUserApplicationCommands() throws Exception {
        UserAccountOperations operations = server.getApplicationContext().getBean(UserAccountOperations.class);

        assertThrows(
                InvalidUserConfigurationException.class,
                () -> operations.create(new CreateUserCommand(
                        "invalid" + (char) 1 + "username",
                        "valid-password",
                        IdentityType.HUMAN,
                        true,
                        Set.of(UserRole.VIEWER))));
        assertThrows(
                InvalidUserConfigurationException.class,
                () -> operations.update(
                        UserId.of(UUID.randomUUID()),
                        new UpdateUserCommand(true, null)));

        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM security.users"));
    }

    /** Apply the baseline USER role while keeping additional human roles explicit. */
    @Test
    void shouldPersistHumanBaselineAndExplicitAdditionalRoles() throws Exception {
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
        assertEquals(201, human.statusCode(),
                () -> "Human creation failed: " + human.statusCode() + " " + human.body());
        assertTrue(human.body().contains("\"USER\""));
        assertTrue(human.body().contains("\"VIEWER\""));
        assertFalse(human.body().contains("\"ADMIN\""));
        assertFalse(human.body().contains("password"));
    }

    /** Apply the baseline BOT role to a system identity without implicitly assigning USER. */
    @Test
    void shouldPersistBotBaselineWithoutUserRole() throws Exception {
        AuthenticatedClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        HttpResponse<String> bot = admin.send("POST", "/api/v1/admin/users", """
                {
                  "username": "bot-%s",
                  "password": "bot-password-for-tests",
                  "identityType": "BOT",
                  "enabled": true,
                  "roles": []
                }
                """.formatted(suffix), true);
        assertEquals(201, bot.statusCode(),
                () -> "BOT creation failed: " + bot.statusCode() + " " + bot.body());
        assertTrue(bot.body().contains("\"BOT\""));
        assertFalse(bot.body().contains("\"USER\""));
    }

    /** Distinguish leaked administrator locks from HTTP session state after a rejected update. */
    @Test
    void shouldContinueAdminMutationsAfterLastAdministratorConflict() throws Exception {
        LastAdministratorScenario scenario = prepareLastAdministratorScenario();

        HttpResponse<String> conflict = scenario.client().send(
                "PUT",
                "/api/v1/admin/users/" + scenario.userId(),
                "{\"enabled\":false,\"roles\":[\"ADMIN\"]}",
                true);
        assertEquals(409, conflict.statusCode(), () ->
                "Expected last-admin conflict, got " + conflict.statusCode() + " " + conflict.body());

        long retainedLocks = administratorStateLockCount();
        String retainedLockDetails = retainedLocks == 0L ? "none" : administratorStateLocks();
        assertEquals(0L, retainedLocks, () ->
                "Rejected last-admin update retained PostgreSQL administrator-state locks after the HTTP response; locks="
                        + retainedLockDetails);

        AuthenticatedClient freshSession = login(scenario.username(), scenario.password());
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> freshSessionBot = freshSession.send("POST", "/api/v1/admin/users", """
                {
                  "username": "post-conflict-fresh-bot-%s",
                  "password": "bot-password-for-tests",
                  "identityType": "BOT",
                  "enabled": true,
                  "roles": []
                }
                """.formatted(suffix), true);
        HttpResponse<String> originalSessionBot = scenario.client().send("POST", "/api/v1/admin/users", """
                {
                  "username": "post-conflict-original-bot-%s",
                  "password": "bot-password-for-tests",
                  "identityType": "BOT",
                  "enabled": true,
                  "roles": []
                }
                """.formatted(suffix), true);

        assertAll(
                () -> assertEquals(201, freshSessionBot.statusCode(), () ->
                        "Fresh-session BOT creation failed after conflict: "
                                + freshSessionBot.statusCode() + " " + freshSessionBot.body()),
                () -> assertEquals(201, originalSessionBot.statusCode(), () ->
                        "Original-session BOT creation failed after conflict: "
                                + originalSessionBot.statusCode() + " " + originalSessionBot.body()),
                () -> assertEquals(1L, enabledAdministratorCount()));
    }

    /** Reject cookie-authenticated mutations without CSRF proof before any state change occurs. */
    @Test
    void shouldRequireCsrfForCookieAuthenticatedAdminMutation() throws Exception {
        AuthenticatedClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        assertEquals(200, admin.send("GET", "/api/v1/admin/users", null, false).statusCode());
        String username = "csrf-rejected-" + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {
                  "username": "%s",
                  "password": "viewer-password-for-tests",
                  "identityType": "HUMAN",
                  "enabled": true,
                  "roles": ["VIEWER"]
                }
                """.formatted(username);

        HttpResponse<String> rejected = admin.send("POST", "/api/v1/admin/users", body, false);

        assertEquals(403, rejected.statusCode(),
                () -> "Mutation without CSRF proof returned " + rejected.statusCode() + " " + rejected.body());
        assertEquals(0L, scalarLong(
                "SELECT COUNT(*) FROM security.users WHERE username = '" + username + "'"));
    }

    /** Enforce ADMIN independently of frontend routing for an authenticated VIEWER. */
    @Test
    void shouldRejectViewerFromAdminApi() throws Exception {
        AuthenticatedClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String username = "viewer-rbac-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = admin.send("POST", "/api/v1/admin/users", """
                {
                  "username": "%s",
                  "password": "viewer-password-for-tests",
                  "identityType": "HUMAN",
                  "enabled": true,
                  "roles": ["VIEWER"]
                }
                """.formatted(username), true);
        assertEquals(201, created.statusCode(),
                () -> "VIEWER creation failed: " + created.statusCode() + " " + created.body());

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
        HttpResponse<String> response = httpClient.send(allowed, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("http://localhost:5173", response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals("true", response.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
        assertNotEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());

        HttpRequest denied = HttpRequest.newBuilder(server.getURI().resolve("/api/v1/auth/login"))
                .timeout(HTTP_TIMEOUT)
                .header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> deniedResponse = httpClient.send(denied, HttpResponse.BodyHandlers.ofString());
        assertFalse(deniedResponse.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        assertFalse(deniedResponse.headers().firstValue("Access-Control-Allow-Credentials").isPresent());
    }

    /** Reject disabling the only remaining enabled administrator. */
    @Test
    void shouldPreventDisablingLastEnabledAdministrator() throws Exception {
        LastAdministratorScenario scenario = prepareLastAdministratorScenario();

        HttpResponse<String> response = scenario.client().send(
                "PUT",
                "/api/v1/admin/users/" + scenario.userId(),
                "{\"enabled\":false,\"roles\":[\"ADMIN\"]}",
                true);
        long enabledAdmins = enabledAdministratorCount();

        assertEquals(409, response.statusCode(), () ->
                "Expected last-admin disable conflict, got " + response.statusCode() + " " + response.body()
                        + "; enabledAdmins=" + enabledAdmins);
        assertEquals(1L, enabledAdmins);
    }

    /** Reject removing ADMIN from the only remaining enabled administrator. */
    @Test
    void shouldPreventDemotingLastEnabledAdministrator() throws Exception {
        LastAdministratorScenario scenario = prepareLastAdministratorScenario();

        HttpResponse<String> response = scenario.client().send(
                "PUT",
                "/api/v1/admin/users/" + scenario.userId(),
                "{\"enabled\":true,\"roles\":[\"VIEWER\"]}",
                true);
        long enabledAdmins = enabledAdministratorCount();

        assertEquals(409, response.statusCode(), () ->
                "Expected last-admin demotion conflict, got " + response.statusCode() + " " + response.body()
                        + "; enabledAdmins=" + enabledAdmins);
        assertEquals(1L, enabledAdmins);
    }

    /** Serialize concurrent ADMIN demotions so exactly one enabled administrator remains. */
    @Test
    void shouldProtectLastEnabledAdministratorDuringConcurrentUpdates() throws Exception {
        AuthenticatedClient bootstrapAdmin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        UUID bootstrapAdminId = UUID.fromString(
                scalarString("SELECT id::text FROM security.users WHERE username = '" + ADMIN_USERNAME + "'"));
        String peerName = "concurrent-admin-" + UUID.randomUUID().toString().substring(0, 8);
        String peerPassword = "concurrent-admin-password-for-tests";
        HttpResponse<String> created = bootstrapAdmin.send("POST", "/api/v1/admin/users", """
                {
                  "username": "%s",
                  "password": "%s",
                  "identityType": "HUMAN",
                  "enabled": true,
                  "roles": ["ADMIN"]
                }
                """.formatted(peerName, peerPassword), true);
        assertEquals(201, created.statusCode());
        UUID peerId = extractId(created.body());
        AuthenticatedClient peerAdmin = login(peerName, peerPassword);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> bootstrapUpdate = executor.submit(() -> {
                ready.countDown();
                start.await();
                return bootstrapAdmin.send(
                        "PUT",
                        "/api/v1/admin/users/" + bootstrapAdminId,
                        "{\"enabled\":false,\"roles\":[\"ADMIN\"]}",
                        true).statusCode();
            });
            Future<Integer> peerUpdate = executor.submit(() -> {
                ready.countDown();
                start.await();
                return peerAdmin.send(
                        "PUT",
                        "/api/v1/admin/users/" + peerId,
                        "{\"enabled\":false,\"roles\":[\"ADMIN\"]}",
                        true).statusCode();
            });

            assertTrue(ready.await(HTTP_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            Set<Integer> statuses = new HashSet<>(List.of(bootstrapUpdate.get(), peerUpdate.get()));
            assertEquals(Set.of(200, 409), statuses);
            assertEquals(1, scalarLong("""
                    SELECT COUNT(*)
                      FROM security.users u
                      JOIN security.user_roles r ON r.user_id = u.id
                     WHERE u.enabled = TRUE
                       AND r.role = 'ADMIN'
                    """));
        }
    }

    /** Update a non-administrator account through the ADMIN HTTP boundary. */
    @Test
    void shouldUpdateViewerAccountThroughAdminApi() throws Exception {
        AuthenticatedClient admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String username = "update-viewer-" + UUID.randomUUID().toString().substring(0, 8);
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
        assertEquals(201, created.statusCode(), () ->
                "Viewer creation failed: " + created.statusCode() + " " + created.body());
        UUID userId = extractId(created.body());

        HttpResponse<String> disabled = admin.send("PUT", "/api/v1/admin/users/" + userId, """
                {"enabled": false, "roles": ["VIEWER"]}
                """, true);

        assertEquals(200, disabled.statusCode(), () ->
                "Viewer update failed: " + disabled.statusCode() + " " + disabled.body());
        assertTrue(disabled.body().contains("\"enabled\":false"));
        assertEquals("false", scalarString("SELECT enabled::text FROM security.users WHERE id = '" + userId + "'"));
    }

    /** Disable future authentication while already issued short-lived JWTs remain stateless until expiry. */
    @Test
    void shouldPreventFutureLoginAfterAccountDisablement() throws Exception {
        UserAccountOperations operations = server.getApplicationContext().getBean(UserAccountOperations.class);
        String username = "disable-me-" + UUID.randomUUID().toString().substring(0, 8);
        String password = "viewer-password-for-tests";
        var account = operations.create(new CreateUserCommand(
                username,
                password,
                IdentityType.HUMAN,
                true,
                Set.of(UserRole.VIEWER)));
        AuthenticatedClient viewer = login(username, password);

        operations.update(account.id(), new UpdateUserCommand(false, Set.of(UserRole.VIEWER)));

        assertEquals(200, viewer.send("GET", "/api/v1/auth/me", null, false).statusCode());
        assertEquals(401, loginResponse(username, password).statusCode());
    }

    private LastAdministratorScenario prepareLastAdministratorScenario() throws Exception {
        AuthenticatedClient bootstrapAdmin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String recoveryAdminName = "recovery-admin-" + UUID.randomUUID().toString().substring(0, 8);
        String recoveryPassword = "recovery-admin-password-for-tests";
        HttpResponse<String> created = bootstrapAdmin.send("POST", "/api/v1/admin/users", """
                {
                  "username": "%s",
                  "password": "%s",
                  "identityType": "HUMAN",
                  "enabled": true,
                  "roles": ["ADMIN"]
                }
                """.formatted(recoveryAdminName, recoveryPassword), true);
        assertEquals(201, created.statusCode(), () ->
                "Recovery administrator creation failed: " + created.statusCode() + " " + created.body());
        UUID recoveryAdminId = extractId(created.body());
        UUID bootstrapAdminId = UUID.fromString(
                scalarString("SELECT id::text FROM security.users WHERE username = '" + ADMIN_USERNAME + "'"));
        AuthenticatedClient recoveryAdmin = login(recoveryAdminName, recoveryPassword);

        HttpResponse<String> disabledBootstrap = recoveryAdmin.send(
                "PUT",
                "/api/v1/admin/users/" + bootstrapAdminId,
                "{\"enabled\":false,\"roles\":[\"ADMIN\"]}",
                true);
        assertEquals(200, disabledBootstrap.statusCode(), () ->
                "Bootstrap administrator disable failed: " + disabledBootstrap.statusCode() + " "
                        + disabledBootstrap.body());
        assertEquals(1L, enabledAdministratorCount());
        return new LastAdministratorScenario(
                recoveryAdminId, recoveryAdminName, recoveryPassword, recoveryAdmin);
    }

    private static long enabledAdministratorCount() throws Exception {
        return scalarLong("""
                SELECT COUNT(DISTINCT u.id)
                  FROM security.users u
                  JOIN security.user_roles r ON r.user_id = u.id
                 WHERE u.enabled = TRUE
                   AND r.role = 'ADMIN'
                """);
    }

    private static long administratorStateLockCount() throws Exception {
        return scalarLong("""
                SELECT COUNT(*)
                  FROM pg_locks l
                  JOIN pg_class c ON c.oid = l.relation
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'security'
                   AND c.relname IN ('users', 'user_roles')
                   AND l.mode = 'ShareRowExclusiveLock'
                   AND l.granted
                """);
    }

    private static String administratorStateLocks() throws Exception {
        return scalarString("""
                SELECT COALESCE(string_agg(format('%s:%s:pid=%s', c.relname, l.mode, l.pid), ', '), 'none')
                  FROM pg_locks l
                  JOIN pg_class c ON c.oid = l.relation
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'security'
                   AND c.relname IN ('users', 'user_roles')
                   AND l.mode = 'ShareRowExclusiveLock'
                   AND l.granted
                """);
    }

    private HttpResponse<String> bearerRequest(String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(server.getURI().resolve("/api/v1/auth/me"))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
        HttpResponse<String> response = loginResponse(username, password);
        assertEquals(200, response.statusCode(), () -> "Login failed: " + response.statusCode() + " " + response.body());
        return new AuthenticatedClient(response);
    }

    private HttpResponse<String> loginResponse(String username, String password) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(server.getURI().resolve("/api/v1/auth/login"))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"username":"%s","password":"%s"}
                        """.formatted(username, password)))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
                Map.entry("micronaut.security.csrf.cookie-same-site", "Strict"),
                Map.entry("micronaut.security.csrf.cookie-path", "/"),
                Map.entry("micronaut.security.csrf.header-name", "X-CSRF-TOKEN"),
                Map.entry("micronaut.security.csrf.filter.regex-pattern", "^(?!/api/v1/auth/login$).*$"),
                Map.entry("micronaut.security.csrf.filter.content-types[0]", "application/json"),
                Map.entry("micronaut.security.csrf.filter.content-types[1]", "application/x-www-form-urlencoded"),
                Map.entry("micronaut.security.csrf.filter.content-types[2]", "multipart/form-data"),
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

    private record LastAdministratorScenario(
            UUID userId, String username, String password, AuthenticatedClient client) {
    }

    private final class AuthenticatedClient {
        private final Map<String, BrowserCookie> cookies = new LinkedHashMap<>();
        private AuthenticatedClient(HttpResponse<?> loginResponse) {
            applySetCookieHeaders(loginResponse);
        }

        private HttpResponse<String> send(String method, String path, String body, boolean includeCsrf) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(server.getURI().resolve(path)).timeout(HTTP_TIMEOUT);
            if (!cookies.isEmpty()) {
                request.header("Cookie", cookies.values().stream()
                        .map(cookie -> cookie.name() + "=" + cookie.value())
                        .collect(Collectors.joining("; ")));
            }
            if (includeCsrf) {
                request.header("X-CSRF-TOKEN", cookie("XSRF-TOKEN").value());
            }
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json");
                request.method(method, HttpRequest.BodyPublishers.ofString(body));
            }
            HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            applySetCookieHeaders(response);
            return response;
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
                boolean httpOnly = false;
                boolean expired = false;
                for (int index = 1; index < segments.length; index++) {
                    String attribute = segments[index].trim();
                    if (attribute.equalsIgnoreCase("HttpOnly")) {
                        httpOnly = true;
                    } else if (attribute.regionMatches(true, 0, "Max-Age=", 0, "Max-Age=".length())) {
                        expired = "0".equals(attribute.substring("Max-Age=".length()).trim());
                    }
                }

                if (expired) {
                    cookies.remove(name);
                } else {
                    cookies.put(name, new BrowserCookie(name, value, httpOnly));
                }
            }
        }

        private boolean hasCookie(String name) {
            return cookies.containsKey(name);
        }

        private BrowserCookie cookie(String name) {
            BrowserCookie cookie = cookies.get(name);
            if (cookie == null) {
                throw new AssertionError("Missing cookie " + name + "; available cookies=" + cookies.keySet());
            }
            return cookie;
        }
    }

    private record BrowserCookie(String name, String value, boolean httpOnly) {
    }
}
