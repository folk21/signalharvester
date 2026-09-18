package io.signalharvester.security.persistence;

import io.signalharvester.common.persistence.SqlResources;
import io.signalharvester.security.model.IdentityType;
import io.signalharvester.security.model.UserAccount;
import io.signalharvester.security.model.UserId;
import io.signalharvester.security.model.UserRole;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Update;

/** Jdbi adapter for security-owned user and role persistence on the active application transaction. */
@Singleton
public final class JdbiSecurityUserRepository implements SecurityUserRepository {

    private static final String SQL_PATH = "security/user";
    private static final String FIND_ALL_SQL = SqlResources.load(SQL_PATH, "find-all");
    private static final String FIND_BY_ID_SQL = SqlResources.load(SQL_PATH, "find-by-id");
    private static final String FIND_BY_USERNAME_SQL = SqlResources.load(SQL_PATH, "find-by-username");
    private static final String FIND_CREDENTIALS_BY_USERNAME_SQL = SqlResources.load(SQL_PATH, "find-credentials-by-username");
    private static final String ANY_ENABLED_ADMIN_SQL = SqlResources.load(SQL_PATH, "any-enabled-admin");
    private static final String LOCK_ADMINISTRATOR_STATE_SQL = SqlResources.load(SQL_PATH, "lock-administrator-state");
    private static final String ANY_OTHER_ENABLED_ADMIN_SQL = SqlResources.load(SQL_PATH, "any-other-enabled-admin");
    private static final String INSERT_SQL = SqlResources.load(SQL_PATH, "insert");
    private static final String UPDATE_SQL = SqlResources.load(SQL_PATH, "update");
    private static final String FIND_ROLES_SQL = SqlResources.load(SQL_PATH, "find-roles");
    private static final String DELETE_ROLES_SQL = SqlResources.load(SQL_PATH, "delete-roles");
    private static final String INSERT_ROLE_SQL = SqlResources.load(SQL_PATH, "insert-role");

    private final Jdbi jdbi;

    public JdbiSecurityUserRepository(@Named("default") Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public List<UserAccount> findAll() {
        return execute("Failed to list users", handle -> {
            List<UserRow> rows = handle.createQuery(FIND_ALL_SQL)
                    .map((resultSet, context) -> mapUserRow(resultSet))
                    .list();
            List<UserAccount> users = new ArrayList<>(rows.size());
            for (UserRow row : rows) {
                users.add(toAccount(handle, row));
            }
            return List.copyOf(users);
        });
    }

    @Override
    public Optional<UserAccount> findById(UserId userId) {
        return execute("Failed to find user", handle -> handle.createQuery(FIND_BY_ID_SQL)
                .bind("userId", userId.value())
                .map((resultSet, context) -> mapUserRow(resultSet))
                .findFirst()
                .map(row -> toAccount(handle, row)));
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return execute("Failed to find user by username", handle -> handle.createQuery(FIND_BY_USERNAME_SQL)
                .bind("username", username)
                .map((resultSet, context) -> mapUserRow(resultSet))
                .findFirst()
                .map(row -> toAccount(handle, row)));
    }

    @Override
    public Optional<StoredCredentials> findCredentialsByUsername(String username) {
        return execute("Failed to load credentials", handle -> handle.createQuery(FIND_CREDENTIALS_BY_USERNAME_SQL)
                .bind("username", username)
                .map((rows, context) -> new CredentialRow(
                        rows.getObject("id", UUID.class),
                        rows.getString("username"),
                        IdentityType.valueOf(rows.getString("identity_type")),
                        rows.getBoolean("enabled"),
                        rows.getString("password_hash")))
                .findFirst()
                .map(row -> new StoredCredentials(
                        UserId.of(row.id()),
                        row.username(),
                        row.identityType(),
                        row.enabled(),
                        row.passwordHash(),
                        readRoles(handle, row.id()))));
    }

    @Override
    public boolean anyEnabledAdminExists() {
        return execute("Failed to inspect enabled administrator state", handle -> handle.createQuery(ANY_ENABLED_ADMIN_SQL)
                .mapTo(Integer.class)
                .findFirst()
                .isPresent());
    }

    @Override
    public void lockAdministratorState() {
        executeVoid("Failed to lock administrator state", handle ->
                handle.createUpdate(LOCK_ADMINISTRATOR_STATE_SQL).execute());
    }

    @Override
    public boolean anyOtherEnabledAdminExists(UserId excludedUserId) {
        return execute("Failed to inspect alternate administrator state", handle ->
                handle.createQuery(ANY_OTHER_ENABLED_ADMIN_SQL)
                        .bind("excludedUserId", excludedUserId.value())
                        .mapTo(Integer.class)
                        .findFirst()
                        .isPresent());
    }

    @Override
    public void insert(UserAccount account, String passwordHash) {
        executeVoid("Failed to create user", handle -> {
            bindAccount(handle.createUpdate(INSERT_SQL), account)
                    .bind("passwordHash", passwordHash)
                    .execute();
            replaceRoles(handle, account.id().value(), account.roles());
        });
    }

    @Override
    public boolean update(UserAccount account) {
        return execute("Failed to update user", handle -> {
            int updated = handle.createUpdate(UPDATE_SQL)
                    .bind("username", account.username())
                    .bind("enabled", account.enabled())
                    .bind("updatedAt", Timestamp.from(account.updatedAt()))
                    .bind("id", account.id().value())
                    .execute();
            if (updated == 0) {
                return false;
            }
            replaceRoles(handle, account.id().value(), account.roles());
            return true;
        });
    }

    private static Update bindAccount(Update update, UserAccount account) {
        return update.bind("id", account.id().value())
                .bind("username", account.username())
                .bind("identityType", account.identityType().name())
                .bind("enabled", account.enabled())
                .bind("createdAt", Timestamp.from(account.createdAt()))
                .bind("updatedAt", Timestamp.from(account.updatedAt()));
    }

    private static UserRow mapUserRow(ResultSet rows) throws SQLException {
        return new UserRow(
                rows.getObject("id", UUID.class),
                rows.getString("username"),
                IdentityType.valueOf(rows.getString("identity_type")),
                rows.getBoolean("enabled"),
                rows.getObject("created_at", OffsetDateTime.class).toInstant(),
                rows.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static UserAccount toAccount(Handle handle, UserRow row) {
        return new UserAccount(
                UserId.of(row.id()),
                row.username(),
                row.identityType(),
                row.enabled(),
                readRoles(handle, row.id()),
                row.createdAt(),
                row.updatedAt());
    }

    private static Set<UserRole> readRoles(Handle handle, UUID userId) {
        EnumSet<UserRole> roles = EnumSet.noneOf(UserRole.class);
        handle.createQuery(FIND_ROLES_SQL)
                .bind("userId", userId)
                .mapTo(String.class)
                .forEach(role -> roles.add(UserRole.valueOf(role)));
        return Set.copyOf(roles);
    }

    private static void replaceRoles(Handle handle, UUID userId, Set<UserRole> roles) {
        handle.createUpdate(DELETE_ROLES_SQL)
                .bind("userId", userId)
                .execute();
        if (roles.isEmpty()) {
            return;
        }
        for (UserRole role : roles.stream().sorted().toList()) {
            handle.createUpdate(INSERT_ROLE_SQL)
                    .bind("userId", userId)
                    .bind("role", role.name())
                    .execute();
        }
    }

    private <T> T execute(String message, HandleFunction<T> operation) {
        try {
            return jdbi.withHandle(handle -> {
                requireActiveTransaction(handle);
                return operation.apply(handle);
            });
        } catch (SecurityPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SecurityPersistenceException(message, exception);
        }
    }

    private void executeVoid(String message, HandleConsumer operation) {
        try {
            jdbi.useHandle(handle -> {
                requireActiveTransaction(handle);
                operation.accept(handle);
            });
        } catch (SecurityPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SecurityPersistenceException(message, exception);
        }
    }

    private static void requireActiveTransaction(Handle handle) {
        if (!handle.isInTransaction()) {
            throw new IllegalStateException("Persistence access requires an application-owned transaction");
        }
    }

    @FunctionalInterface
    private interface HandleFunction<T> {
        T apply(Handle handle);
    }

    @FunctionalInterface
    private interface HandleConsumer {
        void accept(Handle handle);
    }

    private record UserRow(
            UUID id,
            String username,
            IdentityType identityType,
            boolean enabled,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {
    }

    private record CredentialRow(
            UUID id,
            String username,
            IdentityType identityType,
            boolean enabled,
            String passwordHash) {
    }
}
