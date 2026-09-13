package io.signalharvester.results.persistence;

import io.signalharvester.results.model.AnalyzedResult;
import io.signalharvester.results.model.RejectedResult;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Map;

/**
 * PostgreSQL adapter for Results-owned materialized analysis outcomes.
 * Transaction boundaries are owned by the Results application service.
 */
@Singleton
public final class JdbcResultProjectionRepository implements ResultProjectionRepository {

    private static final String UPSERT_ANALYZED_SQL = """
            INSERT INTO results.analyzed_items (
                monitoring_profile_id,
                normalized_item_id,
                analysis_event_id,
                source_event_id,
                raw_item_id,
                source_id,
                information_category,
                external_id,
                title,
                url,
                normalized_content,
                content_type,
                relevant,
                classification,
                score,
                explanation,
                analyzer,
                published_at,
                analyzed_at,
                correlation_id,
                traceparent
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (monitoring_profile_id, normalized_item_id) DO UPDATE SET
                analysis_event_id = EXCLUDED.analysis_event_id,
                source_event_id = EXCLUDED.source_event_id,
                raw_item_id = EXCLUDED.raw_item_id,
                source_id = EXCLUDED.source_id,
                information_category = EXCLUDED.information_category,
                external_id = EXCLUDED.external_id,
                title = EXCLUDED.title,
                url = EXCLUDED.url,
                normalized_content = EXCLUDED.normalized_content,
                content_type = EXCLUDED.content_type,
                relevant = EXCLUDED.relevant,
                classification = EXCLUDED.classification,
                score = EXCLUDED.score,
                explanation = EXCLUDED.explanation,
                analyzer = EXCLUDED.analyzer,
                published_at = EXCLUDED.published_at,
                analyzed_at = EXCLUDED.analyzed_at,
                correlation_id = EXCLUDED.correlation_id,
                traceparent = EXCLUDED.traceparent
            """;
    private static final String DELETE_ATTRIBUTES_SQL = """
            DELETE FROM results.analyzed_item_attributes
             WHERE monitoring_profile_id = ?
               AND normalized_item_id = ?
            """;
    private static final String INSERT_ATTRIBUTE_SQL = """
            INSERT INTO results.analyzed_item_attributes (
                monitoring_profile_id, normalized_item_id, attribute_key, attribute_value
            ) VALUES (?, ?, ?, ?)
            """;
    private static final String DELETE_TAGS_SQL = """
            DELETE FROM results.analyzed_item_tags
             WHERE monitoring_profile_id = ?
               AND normalized_item_id = ?
            """;
    private static final String INSERT_TAG_SQL = """
            INSERT INTO results.analyzed_item_tags (
                monitoring_profile_id, normalized_item_id, tag_ordinal, tag
            ) VALUES (?, ?, ?, ?)
            """;
    private static final String UPSERT_REJECTED_SQL = """
            INSERT INTO results.rejected_items (
                source_event_id,
                analysis_event_id,
                raw_item_id,
                normalized_item_id,
                source_id,
                monitoring_profile_id,
                information_category,
                reason_code,
                explanation,
                rejected_at,
                correlation_id,
                traceparent
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (source_event_id) DO UPDATE SET
                analysis_event_id = EXCLUDED.analysis_event_id,
                raw_item_id = EXCLUDED.raw_item_id,
                normalized_item_id = EXCLUDED.normalized_item_id,
                source_id = EXCLUDED.source_id,
                monitoring_profile_id = EXCLUDED.monitoring_profile_id,
                information_category = EXCLUDED.information_category,
                reason_code = EXCLUDED.reason_code,
                explanation = EXCLUDED.explanation,
                rejected_at = EXCLUDED.rejected_at,
                correlation_id = EXCLUDED.correlation_id,
                traceparent = EXCLUDED.traceparent
            """;

    private final Connection connection;

    public JdbcResultProjectionRepository(@Named("default") Connection connection) {
        this.connection = connection;
    }

    @Override
    public void upsertAnalyzed(AnalyzedResult result) {
        try {
            upsertAnalyzedRow(result);
            replaceAttributes(result);
            replaceTags(result);
        } catch (SQLException | RuntimeException exception) {
            throw new ResultsPersistenceException("Failed to persist analyzed result projection", exception);
        }
    }

    @Override
    public void upsertRejected(RejectedResult result) {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_REJECTED_SQL)) {
            statement.setString(1, result.sourceEventId());
            statement.setString(2, result.analysisEventId());
            statement.setString(3, result.rawItemId());
            setNullableString(statement, 4, result.normalizedItemId().orElse(null));
            statement.setString(5, result.sourceId());
            statement.setString(6, result.monitoringProfileId());
            statement.setString(7, result.informationCategory());
            statement.setString(8, result.reasonCode());
            statement.setString(9, result.explanation());
            statement.setTimestamp(10, Timestamp.from(result.rejectedAt()));
            statement.setString(11, result.correlationId());
            setNullableString(statement, 12, result.traceparent().orElse(null));
            statement.executeUpdate();
        } catch (SQLException | RuntimeException exception) {
            throw new ResultsPersistenceException("Failed to persist rejected result projection", exception);
        }
    }

    private void upsertAnalyzedRow(AnalyzedResult result) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_ANALYZED_SQL)) {
            statement.setString(1, result.monitoringProfileId());
            statement.setString(2, result.normalizedItemId());
            statement.setString(3, result.analysisEventId());
            statement.setString(4, result.sourceEventId());
            statement.setString(5, result.rawItemId());
            statement.setString(6, result.sourceId());
            statement.setString(7, result.informationCategory());
            setNullableString(statement, 8, result.externalId().orElse(null));
            setNullableString(statement, 9, result.title().orElse(null));
            statement.setString(10, result.url());
            statement.setString(11, result.normalizedContent());
            statement.setString(12, result.contentType());
            statement.setBoolean(13, result.relevant());
            statement.setString(14, result.classification());
            statement.setInt(15, result.score());
            statement.setString(16, result.explanation());
            statement.setString(17, result.analyzer());
            if (result.publishedAt().isPresent()) {
                statement.setTimestamp(18, Timestamp.from(result.publishedAt().orElseThrow()));
            } else {
                statement.setNull(18, Types.TIMESTAMP_WITH_TIMEZONE);
            }
            statement.setTimestamp(19, Timestamp.from(result.analyzedAt()));
            statement.setString(20, result.correlationId());
            setNullableString(statement, 21, result.traceparent().orElse(null));
            statement.executeUpdate();
        }
    }

    private void replaceAttributes(AnalyzedResult result) throws SQLException {
        deleteByIdentity(DELETE_ATTRIBUTES_SQL, result);
        if (result.attributes().isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(INSERT_ATTRIBUTE_SQL)) {
            for (Map.Entry<String, String> attribute : result.attributes().entrySet()) {
                statement.setString(1, result.monitoringProfileId());
                statement.setString(2, result.normalizedItemId());
                statement.setString(3, attribute.getKey());
                statement.setString(4, attribute.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void replaceTags(AnalyzedResult result) throws SQLException {
        deleteByIdentity(DELETE_TAGS_SQL, result);
        if (result.tags().isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(INSERT_TAG_SQL)) {
            for (int index = 0; index < result.tags().size(); index++) {
                statement.setString(1, result.monitoringProfileId());
                statement.setString(2, result.normalizedItemId());
                statement.setInt(3, index);
                statement.setString(4, result.tags().get(index));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void deleteByIdentity(String sql, AnalyzedResult result) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, result.monitoringProfileId());
            statement.setString(2, result.normalizedItemId());
            statement.executeUpdate();
        }
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }
}
