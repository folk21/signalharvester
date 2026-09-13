package io.signalharvester.collection.persistence;

import io.signalharvester.collection.run.CollectionRunHistory;
import io.signalharvester.collection.run.CollectionRunResult;
import io.signalharvester.collection.run.CollectionRunStatus;
import io.signalharvester.collection.run.CollectionSourceResult;
import io.signalharvester.collection.run.CollectionSourceStatus;
import io.signalharvester.collection.run.CollectionRunHistoryStore;
import io.signalharvester.configuration.api.SourceId;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL adapter for collection-owned operational run history.
 * Transaction boundaries are owned by collection application use cases.
 */
@Singleton
public final class JdbcCollectionRunHistoryStore implements CollectionRunHistoryStore {
    private static final String INSERT_RUN_SQL = """
            INSERT INTO collection.collection_runs (
                collection_run_id, monitoring_profile_id, information_category,
                started_at, finished_at, status
            ) VALUES (?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_SOURCES_SQL = """
            INSERT INTO collection.collection_run_sources (
                collection_run_id, source_ordinal, source_id, status,
                raw_item_id, event_id, failure_message
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String FIND_RECENT_RUNS_SQL = """
            SELECT collection_run_id, monitoring_profile_id, information_category,
                   started_at, finished_at, status
              FROM collection.collection_runs
             ORDER BY started_at DESC, collection_run_id DESC
             LIMIT ?
            """;
    private static final String FIND_RUN_BY_ID_SQL = """
            SELECT collection_run_id, monitoring_profile_id, information_category,
                   started_at, finished_at, status
              FROM collection.collection_runs
             WHERE collection_run_id = ?
            """;
    private static final String FIND_SOURCES_BY_RUN_ID_SQL = """
            SELECT source_id, status, raw_item_id, event_id, failure_message
              FROM collection.collection_run_sources
             WHERE collection_run_id = ?
             ORDER BY source_ordinal
            """;
    private static final String FIND_SOURCES_BY_RUN_IDS_SQL = """
            SELECT collection_run_id, source_id, status, raw_item_id, event_id, failure_message
              FROM collection.collection_run_sources
             WHERE collection_run_id IN (%s)
             ORDER BY collection_run_id, source_ordinal
            """;

    private final Connection connection;

    public JdbcCollectionRunHistoryStore(@Named("default") Connection connection) {
        this.connection = connection;
    }

    @Override
    public void save(CollectionRunResult result) {
        executeWithTransactionalConnection("Failed to persist collection run history", connection -> {
            UUID collectionRunId = parseRunId(result.collectionRunId());
            insertRun(connection, collectionRunId, result);
            insertSources(connection, collectionRunId, result);
            return null;
        });
    }

    @Override
    public List<CollectionRunResult> findRecent(int limit) {
        requireRecentLimit(limit);
        return executeWithTransactionalConnection("Failed to list collection run history", connection -> {
            List<CollectionRunRow> runs = findRecentRuns(connection, limit);
            if (runs.isEmpty()) {
                return List.of();
            }

            List<UUID> runIds = runs.stream().map(CollectionRunRow::collectionRunId).toList();
            Map<UUID, List<CollectionSourceResult>> sourcesByRunId = findSourcesByRunIds(connection, runIds);

            List<CollectionRunResult> results = new ArrayList<>(runs.size());
            for (CollectionRunRow run : runs) {
                results.add(toResult(run, sourcesByRunId.getOrDefault(run.collectionRunId(), List.of())));
            }
            return List.copyOf(results);
        });
    }

    @Override
    public Optional<CollectionRunResult> findById(UUID collectionRunId) {
        return executeWithTransactionalConnection("Failed to read collection run history", connection -> {
            Optional<CollectionRunRow> run = findRunById(connection, collectionRunId);
            if (run.isEmpty()) {
                return Optional.empty();
            }

            CollectionRunRow value = run.orElseThrow();
            List<CollectionSourceResult> sources = findSourcesByRunId(connection, value.collectionRunId());
            return Optional.of(toResult(value, sources));
        });
    }

    private <T> T executeWithTransactionalConnection(String failureMessage, SqlWork<T> work) {
        try {
            return work.execute(connection);
        } catch (CollectionRunPersistenceException exception) {
            throw exception;
        } catch (SQLException | RuntimeException exception) {
            throw new CollectionRunPersistenceException(failureMessage, exception);
        }
    }

    private static List<CollectionRunRow> findRecentRuns(Connection connection, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_RECENT_RUNS_SQL)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<CollectionRunRow> results = new ArrayList<>();
                while (rows.next()) {
                    results.add(mapRun(rows));
                }
                return List.copyOf(results);
            }
        }
    }

    private static Optional<CollectionRunRow> findRunById(Connection connection, UUID collectionRunId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_RUN_BY_ID_SQL)) {
            statement.setObject(1, collectionRunId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRun(row));
            }
        }
    }

    private static List<CollectionSourceResult> findSourcesByRunId(Connection connection, UUID collectionRunId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_SOURCES_BY_RUN_ID_SQL)) {
            statement.setObject(1, collectionRunId);
            try (ResultSet rows = statement.executeQuery()) {
                List<CollectionSourceResult> results = new ArrayList<>();
                while (rows.next()) {
                    results.add(mapSource(rows));
                }
                return List.copyOf(results);
            }
        }
    }

    /**
     * Loads source outcomes for a bounded set of recent runs in one query so history listing does
     * not degrade into one source query per run.
     */
    private static Map<UUID, List<CollectionSourceResult>> findSourcesByRunIds(
            Connection connection, List<UUID> collectionRunIds) throws SQLException {
        if (collectionRunIds.isEmpty()) {
            return Map.of();
        }

        String placeholders = String.join(", ", Collections.nCopies(collectionRunIds.size(), "?"));
        String sql = FIND_SOURCES_BY_RUN_IDS_SQL.formatted(placeholders);

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < collectionRunIds.size(); index++) {
                statement.setObject(index + 1, collectionRunIds.get(index));
            }

            Map<UUID, List<CollectionSourceResult>> results = new HashMap<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID runId = requiredUuid(rows, "collection_run_id");
                    results.computeIfAbsent(runId, ignored -> new ArrayList<>()).add(mapSource(rows));
                }
            }
            return results;
        }
    }

    private static CollectionRunRow mapRun(ResultSet row) throws SQLException {
        UUID collectionRunId = requiredUuid(row, "collection_run_id");
        return new CollectionRunRow(
                collectionRunId,
                requiredString(row, "monitoring_profile_id", collectionRunId),
                requiredString(row, "information_category", collectionRunId),
                requiredInstant(row, "started_at", collectionRunId),
                requiredInstant(row, "finished_at", collectionRunId),
                parseRunStatus(row.getString("status"), collectionRunId));
    }

    private static CollectionSourceResult mapSource(ResultSet row) throws SQLException {
        UUID sourceId = requiredUuid(row, "source_id");
        try {
            return new CollectionSourceResult(
                    SourceId.of(sourceId),
                    parseSourceStatus(row.getString("status"), sourceId),
                    optional(row.getString("raw_item_id")),
                    optional(row.getString("event_id")),
                    optional(row.getString("failure_message")));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new CollectionRunPersistenceException(
                    "Invalid persisted collection source outcome for source " + sourceId, exception);
        }
    }

    private static CollectionRunResult toResult(CollectionRunRow run, List<CollectionSourceResult> sources) {
        try {
            return new CollectionRunResult(
                    run.collectionRunId().toString(),
                    run.monitoringProfileId(),
                    run.informationCategory(),
                    run.startedAt(),
                    run.finishedAt(),
                    run.status(),
                    sources);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new CollectionRunPersistenceException(
                    "Invalid persisted collection run " + run.collectionRunId(), exception);
        }
    }

    private static void insertRun(Connection connection, UUID collectionRunId, CollectionRunResult result)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_RUN_SQL)) {
            statement.setObject(1, collectionRunId);
            statement.setString(2, result.monitoringProfileId());
            statement.setString(3, result.informationCategory());
            statement.setTimestamp(4, Timestamp.from(result.startedAt()));
            statement.setTimestamp(5, Timestamp.from(result.finishedAt()));
            statement.setString(6, result.status().name());
            statement.executeUpdate();
        }
    }

    private static void insertSources(Connection connection, UUID collectionRunId, CollectionRunResult result)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SOURCES_SQL)) {
            for (int index = 0; index < result.sources().size(); index++) {
                CollectionSourceResult source = result.sources().get(index);
                statement.setObject(1, collectionRunId);
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

    private static UUID parseRunId(String collectionRunId) {
        try {
            return UUID.fromString(collectionRunId);
        } catch (IllegalArgumentException exception) {
            throw new CollectionRunPersistenceException(
                    "Collection run id cannot be stored as PostgreSQL UUID: " + collectionRunId, exception);
        }
    }

    private static UUID requiredUuid(ResultSet row, String column) throws SQLException {
        UUID value = row.getObject(column, UUID.class);
        if (value == null) {
            throw invalidPersistedData(column, null, null);
        }
        return value;
    }

    private static String requiredString(ResultSet row, String column, UUID collectionRunId) throws SQLException {
        String value = row.getString(column);
        if (value == null) {
            throw invalidPersistedData(column, null, collectionRunId);
        }
        return value;
    }

    private static Instant requiredInstant(ResultSet row, String column, UUID collectionRunId) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        if (value == null) {
            throw invalidPersistedData(column, null, collectionRunId);
        }
        return value.toInstant();
    }

    private static CollectionRunStatus parseRunStatus(String value, UUID collectionRunId) {
        try {
            return CollectionRunStatus.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalidPersistedData("status", value, collectionRunId, exception);
        }
    }

    private static CollectionSourceStatus parseSourceStatus(String value, UUID sourceId) {
        try {
            return CollectionSourceStatus.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new CollectionRunPersistenceException(
                    "Invalid persisted collection source status '" + value + "' for source " + sourceId,
                    exception);
        }
    }

    private static CollectionRunPersistenceException invalidPersistedData(
            String column, Object value, UUID collectionRunId) {
        return invalidPersistedData(column, value, collectionRunId, null);
    }

    private static CollectionRunPersistenceException invalidPersistedData(
            String column, Object value, UUID collectionRunId, Throwable cause) {
        String runContext = collectionRunId == null ? "" : " for collection run " + collectionRunId;
        String message = "Invalid persisted collection run value in column '" + column + "'" + runContext
                + ": " + value;
        return cause == null
                ? new CollectionRunPersistenceException(message)
                : new CollectionRunPersistenceException(message, cause);
    }

    private static void requireRecentLimit(int limit) {
        if (limit < CollectionRunHistory.MIN_RECENT_LIMIT || limit > CollectionRunHistory.MAX_RECENT_LIMIT) {
            throw new IllegalArgumentException(
                    "limit must be between " + CollectionRunHistory.MIN_RECENT_LIMIT + " and "
                            + CollectionRunHistory.MAX_RECENT_LIMIT + ": " + limit);
        }
    }

    private static Optional<String> optional(String value) {
        return Optional.ofNullable(value);
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    private record CollectionRunRow(
            UUID collectionRunId,
            String monitoringProfileId,
            String informationCategory,
            Instant startedAt,
            Instant finishedAt,
            CollectionRunStatus status) {}
}
