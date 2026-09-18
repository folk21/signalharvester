package io.signalharvester.analysis.persistence;

import io.signalharvester.common.persistence.SqlResources;
import io.signalharvester.analysis.application.AnalysisItemInspection;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Query;

/** Jdbi read adapter for bounded operational inspection of normalized-item claims. */
@Singleton
public final class JdbiAnalysisItemInspectionRepository implements AnalysisItemInspectionRepository {

    private static final String SQL_PATH = "analysis/inspection";
    private static final String FIND_RECENT_SQL = SqlResources.load(SQL_PATH, "find-recent");
    private static final String FIND_SQL = SqlResources.load(SQL_PATH, "find");

    private final Jdbi jdbi;

    public JdbiAnalysisItemInspectionRepository(@Named("default") Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public List<AnalysisItemInspection> findRecent(
            int limit, Optional<String> monitoringProfileId, Optional<String> sourceId) {
        return execute("Failed to list analysis item inspection state", handle -> {
            Query query = handle.createQuery(FIND_RECENT_SQL).bind("limit", limit);
            bindOptionalText(query, "monitoringProfileId", monitoringProfileId);
            bindOptionalText(query, "sourceId", sourceId);
            return query.map((rows, context) -> map(rows)).list();
        });
    }

    @Override
    public Optional<AnalysisItemInspection> find(String monitoringProfileId, String normalizedItemId) {
        return execute("Failed to read analysis item inspection state", handle -> handle.createQuery(FIND_SQL)
                .bind("monitoringProfileId", monitoringProfileId)
                .bind("normalizedItemId", normalizedItemId)
                .map((row, context) -> map(row))
                .findFirst());
    }

    private static void bindOptionalText(Query query, String name, Optional<String> value) {
        value.ifPresentOrElse(bound -> query.bind(name, bound), () -> query.bindNull(name, Types.VARCHAR));
    }

    private static AnalysisItemInspection map(ResultSet row) throws SQLException {
        return new AnalysisItemInspection(
                row.getString("monitoring_profile_id"),
                row.getString("normalized_item_id"),
                row.getString("source_id"),
                Optional.ofNullable(row.getString("external_id")),
                row.getString("source_url"),
                row.getString("first_raw_item_id"),
                row.getString("first_source_event_id"),
                row.getTimestamp("first_seen_at").toInstant(),
                row.getString("last_raw_item_id"),
                row.getString("last_source_event_id"),
                row.getTimestamp("last_seen_at").toInstant(),
                row.getLong("discovery_count"));
    }

    private <T> T execute(String message, HandleFunction<T> operation) {
        try {
            return jdbi.withHandle(handle -> {
                requireActiveTransaction(handle);
                return operation.apply(handle);
            });
        } catch (AnalysisPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AnalysisPersistenceException(message, exception);
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
}
