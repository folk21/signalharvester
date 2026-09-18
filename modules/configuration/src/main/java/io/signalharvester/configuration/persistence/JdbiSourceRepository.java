package io.signalharvester.configuration.persistence;

import io.signalharvester.common.persistence.SqlResources;
import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.api.SourceType;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.PreparedBatch;
import org.jdbi.v3.core.statement.Update;

/**
 * Persists configured Sources through Jdbi while application use cases retain transaction ownership.
 */
@Singleton
public final class JdbiSourceRepository implements SourceRepository {

    private static final String SQL_PATH = "configuration/source";
    private static final String FIND_ALL_SQL = SqlResources.load(SQL_PATH, "find-all");
    private static final String FIND_ENABLED_SQL = SqlResources.load(SQL_PATH, "find-enabled");
    private static final String FIND_BY_ID_SQL = SqlResources.load(SQL_PATH, "find-by-id");
    private static final String INSERT_SQL = SqlResources.load(SQL_PATH, "insert");
    private static final String UPDATE_SQL = SqlResources.load(SQL_PATH, "update");
    private static final String DELETE_SQL = SqlResources.load(SQL_PATH, "delete");
    private static final String DELETE_SETTINGS_SQL = SqlResources.load(SQL_PATH, "delete-settings");
    private static final String INSERT_SETTING_SQL = SqlResources.load(SQL_PATH, "insert-setting");

    private final Jdbi jdbi;

    public JdbiSourceRepository(@Named("default") Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public List<ConfiguredSource> findAll() {
        return execute("Failed to read configured sources", handle -> querySources(handle, FIND_ALL_SQL, Optional.empty()));
    }

    @Override
    public List<ConfiguredSource> findEnabled() {
        return execute("Failed to read configured sources", handle -> querySources(handle, FIND_ENABLED_SQL, Optional.empty()));
    }

    @Override
    public Optional<ConfiguredSource> findById(SourceId sourceId) {
        List<ConfiguredSource> sources = execute(
                "Failed to read configured sources",
                handle -> querySources(handle, FIND_BY_ID_SQL, Optional.of(sourceId)));
        return sources.stream().findFirst();
    }

    @Override
    public void insert(ConfiguredSource source) {
        executeVoid("Failed to insert configured source", handle -> {
            bindSource(handle.createUpdate(INSERT_SQL), source).execute();
            replaceSettings(handle, source);
        });
    }

    @Override
    public boolean update(ConfiguredSource source) {
        return execute("Failed to update configured source", handle -> {
            int updated = bindSource(handle.createUpdate(UPDATE_SQL), source).execute();
            if (updated == 0) {
                return false;
            }
            replaceSettings(handle, source);
            return true;
        });
    }

    @Override
    public boolean delete(SourceId sourceId) {
        return execute("Failed to delete configured source", handle ->
                handle.createUpdate(DELETE_SQL).bind("sourceId", sourceId.value()).execute() > 0);
    }

    private static List<ConfiguredSource> querySources(
            Handle handle,
            String sql,
            Optional<SourceId> sourceId) {
        var query = handle.createQuery(sql);
        sourceId.ifPresent(id -> query.bind("sourceId", id.value()));
        List<SourceJoinRow> joinedRows = query.map((rows, context) -> mapJoinRow(rows)).list();

        Map<UUID, SourceRow> sourcesById = new LinkedHashMap<>();
        for (SourceJoinRow joinedRow : joinedRows) {
            SourceRow source = sourcesById.computeIfAbsent(
                    joinedRow.id(),
                    ignored -> new SourceRow(
                            joinedRow.id(),
                            joinedRow.name(),
                            joinedRow.type(),
                            joinedRow.location(),
                            joinedRow.enabled()));
            if (joinedRow.settingKey() != null) {
                source.settings.put(joinedRow.settingKey(), joinedRow.settingValue());
            }
        }

        List<ConfiguredSource> sources = new ArrayList<>(sourcesById.size());
        for (SourceRow row : sourcesById.values()) {
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

    private static SourceJoinRow mapJoinRow(ResultSet rows) throws SQLException {
        return new SourceJoinRow(
                rows.getObject("id", UUID.class),
                rows.getString("name"),
                SourceType.valueOf(rows.getString("source_type")),
                URI.create(rows.getString("location")),
                rows.getBoolean("enabled"),
                rows.getString("setting_key"),
                rows.getString("setting_value"));
    }

    private static Update bindSource(
            Update update,
            ConfiguredSource source) {
        return update.bind("id", source.id().value())
                .bind("name", source.name())
                .bind("sourceType", source.type().name())
                .bind("location", source.location().toString())
                .bind("enabled", source.enabled());
    }

    private static void replaceSettings(Handle handle, ConfiguredSource source) {
        handle.createUpdate(DELETE_SETTINGS_SQL)
                .bind("sourceId", source.id().value())
                .execute();
        if (source.settings().isEmpty()) {
            return;
        }

        PreparedBatch batch = handle.prepareBatch(INSERT_SETTING_SQL);
        source.settings().forEach((key, value) -> batch.bind("sourceId", source.id().value())
                .bind("settingKey", key)
                .bind("settingValue", value)
                .add());
        batch.execute();
    }

    private <T> T execute(String message, HandleFunction<T> operation) {
        try {
            return jdbi.withHandle(operation::apply);
        } catch (SourcePersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SourcePersistenceException(message, exception);
        }
    }

    private void executeVoid(String message, HandleConsumer operation) {
        try {
            jdbi.useHandle(operation::accept);
        } catch (SourcePersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SourcePersistenceException(message, exception);
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

    private record SourceJoinRow(
            UUID id,
            String name,
            SourceType type,
            URI location,
            boolean enabled,
            String settingKey,
            String settingValue) {
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
