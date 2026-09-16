package io.signalharvester.security.application;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.security.crypto.PasswordHasher;
import io.signalharvester.security.model.IdentityType;
import io.signalharvester.security.model.UserAccount;
import io.signalharvester.security.model.UserId;
import io.signalharvester.security.model.UserRole;
import io.signalharvester.security.persistence.SecurityPersistenceException;
import io.signalharvester.security.persistence.SecurityUserRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Owns user creation, role invariants, account state changes, and password hashing. */
@Singleton
public final class UserAccountManager implements UserAccountOperations {
    private final SecurityUserRepository repository;
    private final PasswordHasher passwordHasher;
    private final TransactionOperations<Connection> transactions;
    private final Clock clock;

    public UserAccountManager(
            SecurityUserRepository repository,
            PasswordHasher passwordHasher,
            @Named("default") TransactionOperations<Connection> transactions) {
        this(repository, passwordHasher, transactions, Clock.systemUTC());
    }

    UserAccountManager(
            SecurityUserRepository repository,
            PasswordHasher passwordHasher,
            TransactionOperations<Connection> transactions,
            Clock clock) {
        this.repository = repository;
        this.passwordHasher = passwordHasher;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public UserAccount create(CreateUserCommand command) {
        Set<UserRole> roles = normalizeRoles(command.identityType(), command.roles());
        Instant now = clock.instant();
        UserAccount account = new UserAccount(
                UserId.of(UUID.randomUUID()),
                command.username(),
                command.identityType(),
                command.enabled(),
                roles,
                now,
                now);
        String passwordHash;
        try {
            passwordHash = passwordHasher.hash(command.password().toCharArray());
        } catch (IllegalArgumentException exception) {
            throw new InvalidUserConfigurationException(exception.getMessage(), exception);
        }
        try {
            return transactions.executeWrite(status -> {
                repository.insert(account, passwordHash);
                return account;
            });
        } catch (SecurityPersistenceException exception) {
            if (isUniqueViolation(exception)) {
                throw new UsernameAlreadyExistsException(command.username());
            }
            throw exception;
        }
    }

    @Override
    public List<UserAccount> list() {
        return transactions.executeRead(status -> repository.findAll());
    }

    @Override
    public UserAccount get(UserId userId) {
        return transactions.executeRead(status -> repository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId)));
    }

    @Override
    public boolean anyEnabledAdminExists() {
        return transactions.executeRead(status -> repository.anyEnabledAdminExists());
    }

    @Override
    public UserAccount update(UserId userId, UpdateUserCommand command) {
        return transactions.executeWrite(status -> {
            repository.lockAdministratorState();
            UserAccount existing = repository.findById(userId)
                    .orElseThrow(() -> new UserNotFoundException(userId));
            Set<UserRole> roles = normalizeRoles(existing.identityType(), command.roles());
            UserAccount updated = new UserAccount(
                    existing.id(),
                    existing.username(),
                    existing.identityType(),
                    command.enabled(),
                    roles,
                    existing.createdAt(),
                    clock.instant());
            if (isEnabledAdmin(existing)
                    && !isEnabledAdmin(updated)
                    && !repository.anyOtherEnabledAdminExists(userId)) {
                throw new LastEnabledAdministratorException(userId);
            }
            if (!repository.update(updated)) {
                throw new UserNotFoundException(userId);
            }
            return updated;
        });
    }

    private static boolean isEnabledAdmin(UserAccount account) {
        return account.enabled() && account.roles().contains(UserRole.ADMIN);
    }

    private static Set<UserRole> normalizeRoles(IdentityType type, Set<UserRole> requested) {
        EnumSet<UserRole> roles = requested.isEmpty()
                ? EnumSet.noneOf(UserRole.class)
                : EnumSet.copyOf(requested);
        if (type == IdentityType.HUMAN) {
            roles.add(UserRole.USER);
        } else {
            roles.add(UserRole.BOT);
        }
        return Set.copyOf(roles);
    }

    private static boolean isUniqueViolation(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
