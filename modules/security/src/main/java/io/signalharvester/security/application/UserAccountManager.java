package io.signalharvester.security.application;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.operations.api.OperationalChangeCategory;
import io.signalharvester.operations.api.OperationalChangeContext;
import io.signalharvester.operations.api.OperationalChangeJournal;
import io.signalharvester.operations.api.OperationalChangeOutcome;
import io.signalharvester.operations.api.OperationalChangeRecordingException;
import io.signalharvester.operations.api.OperationalChangeRequest;
import io.signalharvester.operations.api.OperationalChangeTargetType;
import io.signalharvester.security.crypto.PasswordHasher;
import io.signalharvester.security.model.IdentityType;
import io.signalharvester.security.model.UserAccount;
import io.signalharvester.security.model.UserId;
import io.signalharvester.security.model.UserRole;
import io.signalharvester.security.persistence.SecurityPersistenceException;
import io.signalharvester.security.persistence.SecurityUserRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns user creation, role invariants, account state changes, password hashing, and safe change journaling. */
@Singleton
public final class UserAccountManager implements UserAccountOperations {
    private static final Logger LOG = LoggerFactory.getLogger(UserAccountManager.class);

    private final SecurityUserRepository repository;
    private final PasswordHasher passwordHasher;
    private final TransactionOperations<Connection> transactions;
    private final Clock clock;
    private final Validator validator;
    private final OperationalChangeJournal changeJournal;

    public UserAccountManager(
            SecurityUserRepository repository,
            PasswordHasher passwordHasher,
            @Named("default") TransactionOperations<Connection> transactions,
            Validator validator,
            OperationalChangeJournal changeJournal) {
        this(repository, passwordHasher, transactions, Clock.systemUTC(), validator, changeJournal);
    }

    UserAccountManager(
            SecurityUserRepository repository,
            PasswordHasher passwordHasher,
            TransactionOperations<Connection> transactions,
            Clock clock,
            Validator validator,
            OperationalChangeJournal changeJournal) {
        this.repository = repository;
        this.passwordHasher = passwordHasher;
        this.transactions = transactions;
        this.clock = clock;
        this.validator = validator;
        this.changeJournal = changeJournal;
    }

    @Override
    public UserAccount create(CreateUserCommand command, OperationalChangeContext changeContext) {
        try {
            validateCommand(command);
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
                    recordApplied(changeContext, account.id().value().toString(), Map.of(), summarizeCreated(account));
                    return account;
                });
            } catch (SecurityPersistenceException exception) {
                if (isUniqueViolation(exception)) {
                    throw new UsernameAlreadyExistsException(command.username());
                }
                throw exception;
            }
        } catch (OperationalChangeRecordingException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            recordRejected(changeContext, "new", Map.of(), summarize(command));
            throw failure;
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
    public UserAccount update(
            UserId userId,
            UpdateUserCommand command,
            OperationalChangeContext changeContext) {
        try {
            validateCommand(command);
            return transactions.executeWrite(status -> repository.withAdministratorStateLock(state -> {
                UserAccount existing = state.findById(userId)
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
                        && !state.anyOtherEnabledAdminExists(userId)) {
                    throw new LastEnabledAdministratorException(userId);
                }
                if (!state.update(updated)) {
                    throw new UserNotFoundException(userId);
                }
                recordApplied(
                        changeContext,
                        userId.value().toString(),
                        summarize(existing),
                        summarize(updated));
                return updated;
            }));
        } catch (OperationalChangeRecordingException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            recordRejected(changeContext, userId.value().toString(), Map.of(), summarize(command));
            throw failure;
        }
    }

    private void recordApplied(
            OperationalChangeContext context,
            String targetId,
            Map<String, String> before,
            Map<String, String> after) {
        if (!context.journalEnabled()) {
            return;
        }
        changeJournal.recordInCurrentTransaction(new OperationalChangeRequest(
                OperationalChangeCategory.SECURITY_ADMINISTRATION,
                OperationalChangeTargetType.USER,
                targetId,
                before,
                after,
                OperationalChangeOutcome.APPLIED,
                context));
    }

    private void recordRejected(
            OperationalChangeContext context,
            String targetId,
            Map<String, String> before,
            Map<String, String> after) {
        if (!context.journalEnabled()) {
            return;
        }
        try {
            changeJournal.record(new OperationalChangeRequest(
                    OperationalChangeCategory.SECURITY_ADMINISTRATION,
                    OperationalChangeTargetType.USER,
                    targetId,
                    before,
                    after,
                    OperationalChangeOutcome.REJECTED,
                    context));
        } catch (RuntimeException journalFailure) {
            LOG.warn("Failed to journal rejected Security mutation targetId={}", targetId, journalFailure);
        }
    }

    private <T> void validateCommand(T command) {
        Objects.requireNonNull(command, "command");
        var violations = validator.validate(command);
        if (!violations.isEmpty()) {
            ConstraintViolationException cause = new ConstraintViolationException(violations);
            throw new InvalidUserConfigurationException(cause.getMessage(), cause);
        }
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

    private static Map<String, String> summarize(UserAccount account) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("username", account.username());
        values.put("identityType", account.identityType().name());
        values.put("enabled", Boolean.toString(account.enabled()));
        values.put("roles", account.roles().stream().map(Enum::name).sorted().reduce((a, b) -> a + "," + b).orElse(""));
        return Map.copyOf(values);
    }

    private static Map<String, String> summarizeCreated(UserAccount account) {
        Map<String, String> values = new LinkedHashMap<>(summarize(account));
        values.put("passwordChanged", "true");
        return Map.copyOf(values);
    }

    private static Map<String, String> summarize(CreateUserCommand command) {
        if (command == null) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("username", command.username() == null ? "" : command.username());
        values.put("identityType", command.identityType() == null ? "" : command.identityType().name());
        values.put("enabled", Boolean.toString(command.enabled()));
        values.put("roles", command.roles() == null
                ? ""
                : command.roles().stream().filter(Objects::nonNull).map(Enum::name).sorted()
                        .reduce((a, b) -> a + "," + b).orElse(""));
        values.put("passwordChanged", "true");
        return Map.copyOf(values);
    }

    private static Map<String, String> summarize(UpdateUserCommand command) {
        if (command == null) {
            return Map.of();
        }
        return Map.of(
                "enabled", Boolean.toString(command.enabled()),
                "roles", command.roles() == null
                        ? ""
                        : command.roles().stream().filter(Objects::nonNull).map(Enum::name).sorted()
                                .reduce((a, b) -> a + "," + b).orElse(""));
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
