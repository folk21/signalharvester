package io.signalharvester.security.persistence;

import io.signalharvester.security.model.IdentityType;
import io.signalharvester.security.model.UserAccount;
import io.signalharvester.security.model.UserId;
import io.signalharvester.security.model.UserRole;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
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

/** JDBC adapter for security-owned user and role persistence on the active transaction connection. */
@Singleton
public final class JdbcSecurityUserRepository implements SecurityUserRepository {
    private static final String BASE_SELECT = """
            SELECT u.id, u.username, u.identity_type, u.enabled, u.created_at, u.updated_at
            FROM security.users u
            """;

    private final Connection connection;

    public JdbcSecurityUserRepository(@Named("default") Connection connection) {
        this.connection = connection;
    }

    @Override
    public List<UserAccount> findAll() {
        try (PreparedStatement statement = connection.prepareStatement(
                BASE_SELECT + " ORDER BY LOWER(u.username), u.id")) {
            try (ResultSet rows = statement.executeQuery()) {
                List<UserAccount> users = new ArrayList<>();
                while (rows.next()) {
                    users.add(readAccount(connection, rows));
                }
                return List.copyOf(users);
            }
        } catch (SQLException exception) {
            throw new SecurityPersistenceException("Failed to list users", exception);
        }
    }

    @Override
    public Optional<UserAccount> findById(UserId userId) {
        try (PreparedStatement statement = connection.prepareStatement(BASE_SELECT + " WHERE u.id = ?")) {
            statement.setObject(1, userId.value());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readAccount(connection, rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new SecurityPersistenceException("Failed to find user", exception);
        }
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        try (PreparedStatement statement = connection.prepareStatement(
                BASE_SELECT + " WHERE LOWER(u.username) = LOWER(?)")) {
            statement.setString(1, username);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readAccount(connection, rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new SecurityPersistenceException("Failed to find user by username", exception);
        }
    }

    @Override
    public Optional<StoredCredentials> findCredentialsByUsername(String username) {
        String sql = """
                SELECT id, username, identity_type, enabled, password_hash
                FROM security.users
                WHERE LOWER(username) = LOWER(?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                UUID id = rows.getObject("id", UUID.class);
                return Optional.of(new StoredCredentials(
                        UserId.of(id),
                        rows.getString("username"),
                        IdentityType.valueOf(rows.getString("identity_type")),
                        rows.getBoolean("enabled"),
                        rows.getString("password_hash"),
                        readRoles(connection, id)));
            }
        } catch (SQLException exception) {
            throw new SecurityPersistenceException("Failed to load credentials", exception);
        }
    }

    @Override
    public boolean anyEnabledAdminExists() {
        String sql = """
                SELECT 1
                  FROM security.users u
                  JOIN security.user_roles r ON r.user_id = u.id
                 WHERE u.enabled = TRUE
                   AND r.role = 'ADMIN'
                 LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            return rows.next();
        } catch (SQLException exception) {
            throw new SecurityPersistenceException("Failed to inspect enabled administrator state", exception);
        }
    }

    @Override
    public void lockAdministratorState() {
        String sql = "LOCK TABLE security.users, security.user_roles IN SHARE ROW EXCLUSIVE MODE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        } catch (SQLException exception) {
            throw new SecurityPersistenceException("Failed to lock administrator state", exception);
        }
    }

    @Override
    public boolean anyOtherEnabledAdminExists(UserId excludedUserId) {
        String sql = """
                SELECT 1
                  FROM security.users u
                  JOIN security.user_roles r ON r.user_id = u.id
                 WHERE u.enabled = TRUE
                   AND r.role = 'ADMIN'
                   AND u.id <> ?
                 LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, excludedUserId.value());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException exception) {
            throw new SecurityPersistenceException("Failed to inspect alternate administrator state", exception);
        }
    }

    @Override
    public void insert(UserAccount account, String passwordHash) {
        String sql = """
                INSERT INTO security.users
                    (id, username, identity_type, enabled, password_hash, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, account.id().value());
            statement.setString(2, account.username());
            statement.setString(3, account.identityType().name());
            statement.setBoolean(4, account.enabled());
            statement.setString(5, passwordHash);
            statement.setTimestamp(6, Timestamp.from(account.createdAt()));
            statement.setTimestamp(7, Timestamp.from(account.updatedAt()));
            statement.executeUpdate();
            replaceRoles(connection, account.id().value(), account.roles());
        } catch (SQLException exception) {
            throw new SecurityPersistenceException("Failed to create user", exception);
        }
    }

    @Override
    public boolean update(UserAccount account) {
        String sql = """
                UPDATE security.users
                SET username = ?, enabled = ?, updated_at = ?
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, account.username());
            statement.setBoolean(2, account.enabled());
            statement.setTimestamp(3, Timestamp.from(account.updatedAt()));
            statement.setObject(4, account.id().value());
            if (statement.executeUpdate() == 0) {
                return false;
            }
            replaceRoles(connection, account.id().value(), account.roles());
            return true;
        } catch (SQLException exception) {
            throw new SecurityPersistenceException("Failed to update user", exception);
        }
    }

    private static UserAccount readAccount(Connection connection, ResultSet rows) throws SQLException {
        UUID id = rows.getObject("id", UUID.class);
        return new UserAccount(
                UserId.of(id),
                rows.getString("username"),
                IdentityType.valueOf(rows.getString("identity_type")),
                rows.getBoolean("enabled"),
                readRoles(connection, id),
                rows.getObject("created_at", OffsetDateTime.class).toInstant(),
                rows.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static Set<UserRole> readRoles(Connection connection, UUID userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT role FROM security.user_roles WHERE user_id = ? ORDER BY role")) {
            statement.setObject(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                EnumSet<UserRole> roles = EnumSet.noneOf(UserRole.class);
                while (rows.next()) {
                    roles.add(UserRole.valueOf(rows.getString("role")));
                }
                return Set.copyOf(roles);
            }
        }
    }

    private static void replaceRoles(Connection connection, UUID userId, Set<UserRole> roles) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM security.user_roles WHERE user_id = ?")) {
            delete.setObject(1, userId);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO security.user_roles (user_id, role) VALUES (?, ?)")) {
            for (UserRole role : roles.stream().sorted().toList()) {
                insert.setObject(1, userId);
                insert.setString(2, role.name());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }
}
