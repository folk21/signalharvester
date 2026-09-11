package io.signalharvester.collection.persistence;

import io.signalharvester.collection.run.CollectionRunHistoryStore;
import io.signalharvester.collection.run.CollectionRunResult;
import io.signalharvester.collection.run.CollectionRunStatus;
import io.signalharvester.collection.run.CollectionSourceResult;
import io.signalharvester.collection.run.CollectionSourceStatus;
import io.signalharvester.configuration.api.SourceId;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** PostgreSQL adapter for collection-owned operational run history. */
@Singleton
public final class JdbcCollectionRunHistoryStore implements CollectionRunHistoryStore {
    private final DataSource dataSource;

    public JdbcCollectionRunHistoryStore(@Named("default") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void save(CollectionRunResult result) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                insertRun(connection, result);
                insertSources(connection, result);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException exception) {
            throw new CollectionRunPersistenceException("Failed to persist collection run history", exception);
        }
    }

    @Override
    public List<CollectionRunResult> findRecent(int limit) {
        String sql = """
                SELECT collection_run_id
                  FROM collection.collection_runs
                 ORDER BY started_at DESC, collection_run_id DESC
                 LIMIT ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            List<String> runIds = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    runIds.add(rows.getObject(1, UUID.class).toString());
                }
            }
            List<CollectionRunResult> results = new ArrayList<>(runIds.size());
            for (String runId : runIds) {
                findById(connection, runId).ifPresent(results::add);
            }
            return List.copyOf(results);
        } catch (SQLException exception) {
            throw new CollectionRunPersistenceException("Failed to list collection run history", exception);
        }
    }

    @Override
    public Optional<CollectionRunResult> findById(String collectionRunId) {
        try (Connection connection = dataSource.getConnection()) {
            return findById(connection, collectionRunId);
        } catch (SQLException exception) {
            throw new CollectionRunPersistenceException("Failed to read collection run history", exception);
        }
    }

    private Optional<CollectionRunResult> findById(Connection connection, String collectionRunId) throws SQLException {
        String sql = """
                SELECT collection_run_id, monitoring_profile_id, information_category,
                       started_at, finished_at, status
                  FROM collection.collection_runs
                 WHERE collection_run_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.fromString(collectionRunId));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new CollectionRunResult(
                        row.getObject("collection_run_id", UUID.class).toString(),
                        row.getString("monitoring_profile_id"),
                        row.getString("information_category"),
                        row.getTimestamp("started_at").toInstant(),
                        row.getTimestamp("finished_at").toInstant(),
                        CollectionRunStatus.valueOf(row.getString("status")),
                        findSources(connection, collectionRunId)));
            }
        }
    }

    private List<CollectionSourceResult> findSources(Connection connection, String collectionRunId) throws SQLException {
        String sql = """
                SELECT source_id, status, raw_item_id, event_id, failure_message
                  FROM collection.collection_run_sources
                 WHERE collection_run_id = ?
                 ORDER BY source_ordinal
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.fromString(collectionRunId));
            try (ResultSet rows = statement.executeQuery()) {
                List<CollectionSourceResult> results = new ArrayList<>();
                while (rows.next()) {
                    results.add(new CollectionSourceResult(
                            SourceId.of(rows.getObject("source_id", UUID.class)),
                            CollectionSourceStatus.valueOf(rows.getString("status")),
                            optional(rows.getString("raw_item_id")),
                            optional(rows.getString("event_id")),
                            optional(rows.getString("failure_message"))));
                }
                return List.copyOf(results);
            }
        }
    }

    private static void insertRun(Connection connection, CollectionRunResult result) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO collection.collection_runs (
                    collection_run_id, monitoring_profile_id, information_category,
                    started_at, finished_at, status
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.fromString(result.collectionRunId()));
            statement.setString(2, result.monitoringProfileId());
            statement.setString(3, result.informationCategory());
            statement.setTimestamp(4, Timestamp.from(result.startedAt()));
            statement.setTimestamp(5, Timestamp.from(result.finishedAt()));
            statement.setString(6, result.status().name());
            statement.executeUpdate();
        }
    }

    private static void insertSources(Connection connection, CollectionRunResult result) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO collection.collection_run_sources (
                    collection_run_id, source_ordinal, source_id, status,
                    raw_item_id, event_id, failure_message
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (int index = 0; index < result.sources().size(); index++) {
                CollectionSourceResult source = result.sources().get(index);
                statement.setObject(1, UUID.fromString(result.collectionRunId()));
                statement.setInt(2, index);
                statement.setObject(3, source.sourceId().value());
                statement.setString(4, source.status().name());
                statement.setString(5, source.rawItemId().orElse(null));
                statement.setString(6, source.eventId().orElse(null));
                statement.setString(7, source.failureMessage().orElse(null));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static Optional<String> optional(String value) {
        return Optional.ofNullable(value);
    }
}
