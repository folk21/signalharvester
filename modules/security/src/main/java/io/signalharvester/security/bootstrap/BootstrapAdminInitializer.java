package io.signalharvester.security.bootstrap;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import io.signalharvester.security.application.CreateUserCommand;
import io.signalharvester.security.application.UserAccountOperations;
import io.signalharvester.security.application.UsernameAlreadyExistsException;
import io.signalharvester.security.model.IdentityType;
import io.signalharvester.security.model.UserRole;
import jakarta.inject.Singleton;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Creates the first explicit administrator from deployment-provided credentials when no enabled administrator is available. */
@Singleton
@Requires(property = "micronaut.security.enabled", value = "true")
public final class BootstrapAdminInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final UserAccountOperations operations;
    private final String username;
    private final String password;

    public BootstrapAdminInitializer(
            UserAccountOperations operations,
            @Value("${signalharvester.security.bootstrap.username:}") String username,
            @Value("${signalharvester.security.bootstrap.password:}") String password) {
        this.operations = operations;
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
    }

    /** Ensures a deployment can bootstrap its first administrator without repository default credentials. */
    @EventListener
    public void onStartup(StartupEvent event) {
        if (operations.anyEnabledAdminExists()) {
            return;
        }
        if (username.isEmpty() && password.isEmpty()) {
            LOGGER.warn("Security is enabled but no enabled ADMIN exists and bootstrap credentials are not configured");
            return;
        }
        if (username.isEmpty() || password.isEmpty()) {
            throw new IllegalStateException("Both security bootstrap username and password must be configured together");
        }
        try {
            operations.create(new CreateUserCommand(
                    username,
                    password,
                    IdentityType.HUMAN,
                    true,
                    Set.of(UserRole.USER, UserRole.VIEWER, UserRole.ADMIN)));
            LOGGER.info("Bootstrapped initial administrator login={}", username);
        } catch (UsernameAlreadyExistsException exception) {
            if (!operations.anyEnabledAdminExists()) {
                throw new IllegalStateException(
                        "No enabled ADMIN exists and the configured bootstrap username is already in use",
                        exception);
            }
        }
    }
}
