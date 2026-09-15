package io.signalharvester.security.authentication;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.AuthenticationRequest;
import io.micronaut.security.authentication.AuthenticationResponse;
import io.micronaut.security.authentication.provider.HttpRequestExecutorAuthenticationProvider;
import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.security.crypto.PasswordHasher;
import io.signalharvester.security.persistence.SecurityUserRepository;
import io.signalharvester.security.persistence.StoredCredentials;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Authenticates local accounts from security-owned PostgreSQL credentials on the blocking executor. */
@Singleton
@Requires(property = "micronaut.security.enabled", value = "true")
public final class DatabaseAuthenticationProvider implements HttpRequestExecutorAuthenticationProvider<Object> {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseAuthenticationProvider.class);

    private static final char[] DUMMY_PASSWORD = "signalharvester-invalid-credential-probe".toCharArray();

    private final SecurityUserRepository users;
    private final PasswordHasher passwordHasher;
    private final TransactionOperations<Connection> transactions;
    private final String dummyPasswordHash;

    public DatabaseAuthenticationProvider(
            SecurityUserRepository users,
            PasswordHasher passwordHasher,
            @Named("default") TransactionOperations<Connection> transactions) {
        this.users = users;
        this.passwordHasher = passwordHasher;
        this.transactions = transactions;
        this.dummyPasswordHash = passwordHasher.hash(DUMMY_PASSWORD.clone());
    }

    @Override
    public AuthenticationResponse authenticate(
            HttpRequest<Object> requestContext,
            AuthenticationRequest<String, String> authRequest) {
        String login = authRequest.getIdentity() == null ? "" : authRequest.getIdentity().trim();
        char[] password = authRequest.getSecret() == null ? new char[0] : authRequest.getSecret().toCharArray();
        try {
            if (!isValidLogin(login)) {
                verifyDummyPassword(password);
                LOGGER.info("Authentication rejected");
                return AuthenticationResponse.failure("Invalid credentials");
            }
            StoredCredentials credentials = transactions.executeRead(
                    status -> users.findCredentialsByUsername(login).orElse(null));
            boolean passwordMatches = credentials == null
                    ? verifyDummyPassword(password)
                    : passwordHasher.matches(password, credentials.passwordHash());
            if (credentials == null || !credentials.enabled() || !passwordMatches) {
                LOGGER.info("Authentication rejected");
                return AuthenticationResponse.failure("Invalid credentials");
            }
            List<String> roles = credentials.roles().stream().map(Enum::name).sorted().toList();
            LOGGER.info("Authentication succeeded principalId={} login={}", credentials.userId().value(), credentials.username());
            return AuthenticationResponse.success(
                    credentials.userId().value().toString(),
                    roles,
                    Map.of(
                            "username", credentials.username(),
                            "identityType", credentials.identityType().name()));
        } catch (IllegalArgumentException exception) {
            LOGGER.info("Authentication rejected");
            return AuthenticationResponse.failure("Invalid credentials");
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private boolean verifyDummyPassword(char[] password) {
        return passwordHasher.matches(password, dummyPasswordHash);
    }

    private static boolean isValidLogin(String login) {
        return !login.isEmpty()
                && login.length() <= 200
                && login.chars().noneMatch(Character::isISOControl);
    }
}
