package io.signalharvester.configuration.persistence;

import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.api.SourceType;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Persists configured sources through transaction-aware JDBC connections in the configuration-owned PostgreSQL schema.
 * Write transaction boundaries are owned by the configuration application use cases.
 */
@Singleton
public final class JdbcSourceRepository implements SourceRepository {

    private static final String SELECT_COLUMNS = """
            SELECT s.id, s.name, s.source_type, s.location, s.enabled,
                   ss.setting_key, ss.setting_value
              FROM configuration.sources s
              LEFT JOIN configuration.source_settings ss ON ss.source_id = s.id
            """;

    private final DataSource dataSource;

    public JdbcSourceRepository(@Named("default") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<ConfiguredSource> findAll() {
        return querySources(SELECT_COLUMNS + " ORDER BY s.name, s.id, ss.setting_key", statement -> { });
    }

    @Override
    public List<ConfiguredSource> findEnabled() {
        return querySources(
                SELECT_COLUMNS + " WHERE s.enabled = TRUE ORDER BY s.name, s.id, ss.setting_key",
                statement -> { });
    }

    @Override
    public Optional<ConfiguredSource> findById(SourceId sourceId) {
        List<ConfiguredSource> sources = querySources(
                SELECT_COLUMNS + " WHERE s.id = ? ORDER BY ss.setting_key",
                statement -> statement.setObject(1, sourceId.value()));
        return sources.stream().findFirst();
    }

    @Override
    public void insert(ConfiguredSource source) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO configuration.sources (id, name, source_type, location, enabled)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                bindSource(statement, source);
                statement.executeUpdate();
            }
            replaceSettings(connection, source);
        } catch (SQLException exception) {
            throw new SourcePersistenceException("Failed to insert configured source", exception);
        }
    }

    @Override
    public boolean update(ConfiguredSource source) {
        try (Connection connection = dataSource.getConnection()) {
            int updated;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE configuration.sources
                       SET name = ?, source_type = ?, location = ?, enabled = ?
                     WHERE id = ?
                    """)) {
                statement.setString(1, source.name());
                statement.setString(2, source.type().name());
                statement.setString(3, source.location().toString());
                statement.setBoolean(4, source.enabled());
                statement.setObject(5, source.id().value());
                updated = statement.executeUpdate();
            }
            if (updated == 0) {
                return false;
            }
            replaceSettings(connection, source);
            return true;
        } catch (SQLException exception) {
            throw new SourcePersistenceException("Failed to update configured source", exception);
        }
    }

    @Override
    public boolean delete(SourceId sourceId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM configuration.sources WHERE id = ?")) {
            statement.setObject(1, sourceId.value());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new SourcePersistenceException("Failed to delete configured source", exception);
        }
    }

    private List<ConfiguredSource> querySources(String sql, StatementBinder binder) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return mapSources(resultSet);
            }
        } catch (SQLException | IllegalArgumentException exception) {
            throw new SourcePersistenceException("Failed to read configured sources", exception);
        }
    }

    private static List<ConfiguredSource> mapSources(ResultSet resultSet) throws SQLException {
        Map<UUID, SourceRow> rows = new LinkedHashMap<>();
        while (resultSet.next()) {
            UUID id = resultSet.getObject("id", UUID.class);
            SourceRow row = rows.get(id);
            if (row == null) {
                row = new SourceRow(
                        id,
                        resultSet.getString("name"),
                        SourceType.valueOf(resultSet.getString("source_type")),
                        URI.create(resultSet.getString("location")),
                        resultSet.getBoolean("enabled"));
                rows.put(id, row);
            }
            String key = resultSet.getString("setting_key");
            if (key != null) {
                row.settings.put(key, resultSet.getString("setting_value"));
            }
        }

        List<ConfiguredSource> sources = new ArrayList<>(rows.size());
        for (SourceRow row : rows.values()) {
            sources.add(new ConfiguredSource(
                    SourceId.of(row.id),
                    row.name,
                    row.type,
                    row.location,
                    row.enabled,
                    row.settings));
        }
        return List.copyOf(sources);
    }

    private static void bindSource(PreparedStatement statement, ConfiguredSource source) throws SQLException {
        statement.setObject(1, source.id().value());
        statement.setString(2, source.name());
        statement.setString(3, source.type().name());
        statement.setString(4, source.location().toString());
        statement.setBoolean(5, source.enabled());
    }

    private static void replaceSettings(Connection connection, ConfiguredSource source) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM configuration.source_settings WHERE source_id = ?")) {
            delete.setObject(1, source.id().value());
            delete.executeUpdate();
        }

        if (source.settings().isEmpty()) {
            return;
        }

        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO configuration.source_settings (source_id, setting_key, setting_value)
                VALUES (?, ?, ?)
                """)) {
            for (Map.Entry<String, String> setting : source.settings().entrySet()) {
                insert.setObject(1, source.id().value());
                insert.setString(2, setting.getKey());
                insert.setString(3, setting.getValue());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private static final class SourceRow {
        private final UUID id;
        private final String name;
        private final SourceType type;
        private final URI location;
        private final boolean enabled;
        private final Map<String, String> settings = new LinkedHashMap<>();

        private SourceRow(UUID id, String name, SourceType type, URI location, boolean enabled) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.location = location;
            this.enabled = enabled;
        }
    }
}
