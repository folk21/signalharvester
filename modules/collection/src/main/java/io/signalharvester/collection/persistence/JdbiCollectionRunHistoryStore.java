package io.signalharvester.collection.persistence;

import io.signalharvester.collection.run.CollectionRunHistory;
import io.signalharvester.collection.run.CollectionRunHistoryStore;
import io.signalharvester.collection.run.CollectionRunResult;
import io.signalharvester.collection.run.CollectionRunStatus;
import io.signalharvester.collection.run.CollectionSourceResult;
import io.signalharvester.collection.run.CollectionSourceStatus;
import io.signalharvester.configuration.api.SourceId;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.PreparedBatch;

/**
 * Jdbi adapter for collection-owned operational run history.
 * Transaction boundaries remain owned by Collection application use cases.
 */
@Singleton
public final class JdbiCollectionRunHistoryStore implements CollectionRunHistoryStore {

    private static final String INSERT_RUN_SQL = CollectionRunSql.get("insert-run");
    private static final String INSERT_SOURCE_SQL = CollectionRunSql.get("insert-source");
    private static final String FIND_RECENT_RUNS_SQL = CollectionRunSql.get("find-recent-runs");
    private static final String FIND_RUN_BY_ID_SQL = CollectionRunSql.get("find-run-by-id");
    private static final String FIND_SOURCES_BY_RUN_ID_SQL = CollectionRunSql.get("find-sources-by-run-id");
    private static final String FIND_SOURCES_BY_RUN_IDS_SQL = CollectionRunSql.get("find-sources-by-run-ids");

    private final Jdbi jdbi;

    public JdbiCollectionRunHistoryStore(@Named("default") Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public void save(CollectionRunResult result) {
        executeVoid("Failed to persist collection run history", handle -> {
            UUID collectionRunId = parseRunId(result.collectionRunId());
            insertRun(handle, collectionRunId, result);
            insertSources(handle, collectionRunId, result);
        });
    }

    @Override
    public List<CollectionRunResult> findRecent(int limit) {
        requireRecentLimit(limit);
        return execute("Failed to list collection run history", handle -> {
            List<CollectionRunRow> runs = handle.createQuery(FIND_RECENT_RUNS_SQL)
                    .bind("limit", limit)
                    .map((rows, context) -> mapRun(rows))
                    .list();
            if (runs.isEmpty()) {
                return List.of();
            }

            List<UUID> runIds = runs.stream().map(CollectionRunRow::collectionRunId).toList();
            Map<UUID, List<CollectionSourceResult>> sourcesByRunId = findSourcesByRunIds(handle, runIds);

            List<CollectionRunResult> results = new ArrayList<>(runs.size());
            for (CollectionRunRow run : runs) {
                results.add(toResult(run, sourcesByRunId.getOrDefault(run.collectionRunId(), List.of())));
            }
            return List.copyOf(results);
        });
    }

    @Override
    public Optional<CollectionRunResult> findById(UUID collectionRunId) {
        return execute("Failed to read collection run history", handle -> {
            Optional<CollectionRunRow> run = handle.createQuery(FIND_RUN_BY_ID_SQL)
                    .bind("collectionRunId", collectionRunId)
                    .map((row, context) -> mapRun(row))
                    .findFirst();
            if (run.isEmpty()) {
                return Optional.empty();
            }

            CollectionRunRow value = run.orElseThrow();
            List<CollectionSourceResult> sources = handle.createQuery(FIND_SOURCES_BY_RUN_ID_SQL)
                    .bind("collectionRunId", value.collectionRunId())
                    .map((rows, context) -> mapSource(rows))
                    .list();
            return Optional.of(toResult(value, sources));
        });
    }

    /** Loads source outcomes for a bounded set of recent runs without per-run source queries. */
    private static Map<UUID, List<CollectionSourceResult>> findSourcesByRunIds(
            Handle handle, List<UUID> collectionRunIds) {
        if (collectionRunIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, List<CollectionSourceResult>> results = new HashMap<>();
        handle.createQuery(FIND_SOURCES_BY_RUN_IDS_SQL)
                .bindList("collectionRunIds", collectionRunIds)
                .map((rows, context) -> new RunSourceRow(
                        requiredUuid(rows, "collection_run_id"),
                        mapSource(rows)))
                .forEach(row -> results.computeIfAbsent(row.collectionRunId(), ignored -> new ArrayList<>())
                        .add(row.sourceResult()));
        return results;
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

    private static void insertRun(Handle handle, UUID collectionRunId, CollectionRunResult result) {
        handle.createUpdate(INSERT_RUN_SQL)
                .bind("collectionRunId", collectionRunId)
                .bind("monitoringProfileId", result.monitoringProfileId())
                .bind("informationCategory", result.informationCategory())
                .bind("startedAt", Timestamp.from(result.startedAt()))
                .bind("finishedAt", Timestamp.from(result.finishedAt()))
                .bind("status", result.status().name())
                .execute();
    }

    private static void insertSources(Handle handle, UUID collectionRunId, CollectionRunResult result) {
        if (result.sources().isEmpty()) {
            return;
        }
        PreparedBatch batch = handle.prepareBatch(INSERT_SOURCE_SQL);
        for (int index = 0; index < result.sources().size(); index++) {
            CollectionSourceResult source = result.sources().get(index);
            batch.bind("collectionRunId", collectionRunId)
                    .bind("sourceOrdinal", index)
                    .bind("sourceId", source.sourceId().value())
                    .bind("status", source.status().name());
            bindOptional(batch, "rawItemId", source.rawItemId());
            bindOptional(batch, "eventId", source.eventId());
            bindOptional(batch, "failureMessage", source.failureMessage());
            batch.add();
        }
        batch.execute();
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

    private <T> T execute(String message, HandleFunction<T> operation) {
        try {
            return jdbi.withHandle(handle -> {
                requireActiveTransaction(handle);
                return operation.apply(handle);
            });
        } catch (CollectionRunPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new CollectionRunPersistenceException(message, exception);
        }
    }

    private void executeVoid(String message, HandleConsumer operation) {
        try {
            jdbi.useHandle(handle -> {
                requireActiveTransaction(handle);
                operation.accept(handle);
            });
        } catch (CollectionRunPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new CollectionRunPersistenceException(message, exception);
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

    private record CollectionRunRow(
            UUID collectionRunId,
            String monitoringProfileId,
            String informationCategory,
            Instant startedAt,
            Instant finishedAt,
            CollectionRunStatus status) {
    }

    private record RunSourceRow(UUID collectionRunId, CollectionSourceResult sourceResult) {
    }

    private static void bindOptional(PreparedBatch batch, String name, Optional<String> value) {
        // Jdbi infers PreparedBatch argument types from the first row, so nullable values must stay explicitly typed.
        batch.bindByType(name, value.orElse(null), String.class);
    }

}
