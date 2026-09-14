package io.signalharvester.results.persistence;

import io.signalharvester.results.application.ResultDetail;
import io.signalharvester.results.application.ResultLiveCriteria;
import io.signalharvester.results.application.ResultLiveUpdate;
import io.signalharvester.results.application.ResultQueryCriteria;
import io.signalharvester.results.application.ResultSummary;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PostgreSQL read adapter for bounded result-feed and result-detail queries.
 * The injected connection participates in the application-owned Micronaut transaction.
 */
@Singleton
public final class JdbcResultQueryRepository implements ResultQueryRepository, ResultLiveQueryRepository {

    private static final String SUMMARY_SELECT = """
            SELECT ai.monitoring_profile_id,
                   ai.normalized_item_id,
                   ai.source_id,
                   ai.information_category,
                   ai.external_id,
                   ai.title,
                   ai.url,
                   ai.relevant,
                   ai.classification,
                   ai.score,
                   COALESCE(ARRAY(
                       SELECT attribute.attribute_key
                         FROM results.analyzed_item_attributes attribute
                        WHERE attribute.monitoring_profile_id = ai.monitoring_profile_id
                          AND attribute.normalized_item_id = ai.normalized_item_id
                        ORDER BY attribute.attribute_key
                   ), ARRAY[]::text[]) AS attribute_keys,
                   COALESCE(ARRAY(
                       SELECT attribute.attribute_value
                         FROM results.analyzed_item_attributes attribute
                        WHERE attribute.monitoring_profile_id = ai.monitoring_profile_id
                          AND attribute.normalized_item_id = ai.normalized_item_id
                        ORDER BY attribute.attribute_key
                   ), ARRAY[]::text[]) AS attribute_values,
                   COALESCE(ARRAY(
                       SELECT tag.tag
                         FROM results.analyzed_item_tags tag
                        WHERE tag.monitoring_profile_id = ai.monitoring_profile_id
                          AND tag.normalized_item_id = ai.normalized_item_id
                        ORDER BY tag.tag_ordinal
                   ), ARRAY[]::text[]) AS tags,
                   ai.explanation,
                   ai.analyzer,
                   ai.published_at,
                   ai.analyzed_at
              FROM results.analyzed_items ai
             WHERE 1 = 1
            """;

    private static final String LIVE_SUMMARY_SELECT = """
            SELECT live.live_event_id,
                   ai.monitoring_profile_id,
                   ai.normalized_item_id,
                   ai.source_id,
                   ai.information_category,
                   ai.external_id,
                   ai.title,
                   ai.url,
                   ai.relevant,
                   ai.classification,
                   ai.score,
                   COALESCE(ARRAY(
                       SELECT attribute.attribute_key
                         FROM results.analyzed_item_attributes attribute
                        WHERE attribute.monitoring_profile_id = ai.monitoring_profile_id
                          AND attribute.normalized_item_id = ai.normalized_item_id
                        ORDER BY attribute.attribute_key
                   ), ARRAY[]::text[]) AS attribute_keys,
                   COALESCE(ARRAY(
                       SELECT attribute.attribute_value
                         FROM results.analyzed_item_attributes attribute
                        WHERE attribute.monitoring_profile_id = ai.monitoring_profile_id
                          AND attribute.normalized_item_id = ai.normalized_item_id
                        ORDER BY attribute.attribute_key
                   ), ARRAY[]::text[]) AS attribute_values,
                   COALESCE(ARRAY(
                       SELECT tag.tag
                         FROM results.analyzed_item_tags tag
                        WHERE tag.monitoring_profile_id = ai.monitoring_profile_id
                          AND tag.normalized_item_id = ai.normalized_item_id
                        ORDER BY tag.tag_ordinal
                   ), ARRAY[]::text[]) AS tags,
                   ai.explanation,
                   ai.analyzer,
                   ai.published_at,
                   ai.analyzed_at
              FROM results.live_result_cursors live
              JOIN results.analyzed_items ai
                ON ai.monitoring_profile_id = live.monitoring_profile_id
               AND ai.normalized_item_id = live.normalized_item_id
               AND ai.analysis_event_id = live.analysis_event_id
             WHERE live.live_event_id > ?
               AND live.live_event_id <= ?
            """;

    private static final String CURRENT_LIVE_CURSOR_SQL = """
            SELECT COALESCE(MAX(live_event_id), 0)
              FROM results.live_result_cursors
            """;

    private static final String DETAIL_SQL = """
            SELECT monitoring_profile_id,
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
              FROM results.analyzed_items
             WHERE monitoring_profile_id = ?
               AND normalized_item_id = ?
            """;

    private static final String ATTRIBUTES_SQL = """
            SELECT attribute_key, attribute_value
              FROM results.analyzed_item_attributes
             WHERE monitoring_profile_id = ?
               AND normalized_item_id = ?
             ORDER BY attribute_key
            """;

    private static final String TAGS_SQL = """
            SELECT tag
              FROM results.analyzed_item_tags
             WHERE monitoring_profile_id = ?
               AND normalized_item_id = ?
             ORDER BY tag_ordinal
            """;

    private final Connection connection;

    public JdbcResultQueryRepository(@Named("default") Connection connection) {
        this.connection = connection;
    }

    @Override
    public List<ResultSummary> findRecent(ResultQueryCriteria criteria) {
        StringBuilder sql = new StringBuilder(SUMMARY_SELECT);
        List<Object> parameters = new ArrayList<>();
        appendTextFilter(sql, parameters, "ai.monitoring_profile_id", criteria.monitoringProfileId());
        appendTextFilter(sql, parameters, "ai.source_id", criteria.sourceId());
        appendTextFilter(sql, parameters, "ai.information_category", criteria.informationCategory());
        criteria.relevant().ifPresent(value -> {
            sql.append(" AND ai.relevant = ?\n");
            parameters.add(value);
        });
        appendTextFilter(sql, parameters, "ai.classification", criteria.classification());
        criteria.analyzedFrom().ifPresent(value -> {
            sql.append(" AND ai.analyzed_at >= ?\n");
            parameters.add(value);
        });
        criteria.analyzedTo().ifPresent(value -> {
            sql.append(" AND ai.analyzed_at <= ?\n");
            parameters.add(value);
        });
        sql.append(" ORDER BY ai.analyzed_at DESC, ai.monitoring_profile_id, ai.normalized_item_id LIMIT ?");
        parameters.add(criteria.limit());

        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                List<ResultSummary> results = new ArrayList<>();
                while (rows.next()) {
                    results.add(mapSummary(rows));
                }
                return List.copyOf(results);
            }
        } catch (SQLException | RuntimeException exception) {
            throw new ResultsPersistenceException("Failed to list analyzed results", exception);
        }
    }

    @Override
    public long currentCursor() {
        try (PreparedStatement statement = connection.prepareStatement(CURRENT_LIVE_CURSOR_SQL);
                ResultSet row = statement.executeQuery()) {
            if (!row.next()) {
                throw new SQLException("Live-result cursor query returned no row");
            }
            return row.getLong(1);
        } catch (SQLException | RuntimeException exception) {
            throw new ResultsPersistenceException("Failed to read current live-result cursor", exception);
        }
    }

    @Override
    public List<ResultLiveUpdate> findUpdatesAfter(
            long cursor, long throughCursor, ResultLiveCriteria criteria, int limit) {
        StringBuilder sql = new StringBuilder(LIVE_SUMMARY_SELECT);
        List<Object> parameters = new ArrayList<>();
        parameters.add(cursor);
        parameters.add(throughCursor);
        appendTextFilter(sql, parameters, "ai.monitoring_profile_id", criteria.monitoringProfileId());
        appendTextFilter(sql, parameters, "ai.source_id", criteria.sourceId());
        appendTextFilter(sql, parameters, "ai.information_category", criteria.informationCategory());
        criteria.relevant().ifPresent(value -> {
            sql.append(" AND ai.relevant = ?\n");
            parameters.add(value);
        });
        appendTextFilter(sql, parameters, "ai.classification", criteria.classification());
        sql.append(" ORDER BY live.live_event_id ASC LIMIT ?");
        parameters.add(limit);

        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                List<ResultLiveUpdate> updates = new ArrayList<>();
                while (rows.next()) {
                    updates.add(new ResultLiveUpdate(rows.getLong("live_event_id"), mapSummary(rows)));
                }
                return List.copyOf(updates);
            }
        } catch (SQLException | RuntimeException exception) {
            throw new ResultsPersistenceException("Failed to read live analyzed-result updates", exception);
        }
    }

    @Override
    public Optional<ResultDetail> find(String monitoringProfileId, String normalizedItemId) {
        try (PreparedStatement statement = connection.prepareStatement(DETAIL_SQL)) {
            statement.setString(1, monitoringProfileId);
            statement.setString(2, normalizedItemId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapDetail(
                        row,
                        readAttributes(monitoringProfileId, normalizedItemId),
                        readTags(monitoringProfileId, normalizedItemId)));
            }
        } catch (SQLException | RuntimeException exception) {
            throw new ResultsPersistenceException("Failed to read analyzed result detail", exception);
        }
    }

    private Map<String, String> readAttributes(String monitoringProfileId, String normalizedItemId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ATTRIBUTES_SQL)) {
            statement.setString(1, monitoringProfileId);
            statement.setString(2, normalizedItemId);
            try (ResultSet rows = statement.executeQuery()) {
                Map<String, String> attributes = new LinkedHashMap<>();
                while (rows.next()) {
                    attributes.put(rows.getString("attribute_key"), rows.getString("attribute_value"));
                }
                return Map.copyOf(attributes);
            }
        }
    }

    private List<String> readTags(String monitoringProfileId, String normalizedItemId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TAGS_SQL)) {
            statement.setString(1, monitoringProfileId);
            statement.setString(2, normalizedItemId);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> tags = new ArrayList<>();
                while (rows.next()) {
                    tags.add(rows.getString("tag"));
                }
                return List.copyOf(tags);
            }
        }
    }

    private static ResultSummary mapSummary(ResultSet row) throws SQLException {
        return new ResultSummary(
                row.getString("monitoring_profile_id"),
                row.getString("normalized_item_id"),
                row.getString("source_id"),
                row.getString("information_category"),
                Optional.ofNullable(row.getString("external_id")),
                Optional.ofNullable(row.getString("title")),
                row.getString("url"),
                row.getBoolean("relevant"),
                row.getString("classification"),
                row.getInt("score"),
                readStringMap(row, "attribute_keys", "attribute_values"),
                readStringArray(row, "tags"),
                row.getString("explanation"),
                row.getString("analyzer"),
                optionalInstant(row, "published_at"),
                row.getTimestamp("analyzed_at").toInstant());
    }

    private static ResultDetail mapDetail(ResultSet row, Map<String, String> attributes, List<String> tags)
            throws SQLException {
        return new ResultDetail(
                row.getString("monitoring_profile_id"),
                row.getString("normalized_item_id"),
                row.getString("analysis_event_id"),
                row.getString("source_event_id"),
                row.getString("raw_item_id"),
                row.getString("source_id"),
                row.getString("information_category"),
                Optional.ofNullable(row.getString("external_id")),
                Optional.ofNullable(row.getString("title")),
                row.getString("url"),
                row.getString("normalized_content"),
                row.getString("content_type"),
                attributes,
                row.getBoolean("relevant"),
                row.getString("classification"),
                row.getInt("score"),
                tags,
                row.getString("explanation"),
                row.getString("analyzer"),
                optionalInstant(row, "published_at"),
                row.getTimestamp("analyzed_at").toInstant(),
                row.getString("correlation_id"),
                Optional.ofNullable(row.getString("traceparent")));
    }

    private static void appendTextFilter(
            StringBuilder sql, List<Object> parameters, String column, Optional<String> value) {
        value.ifPresent(item -> {
            sql.append(" AND ").append(column).append(" = ?\n");
            parameters.add(item);
        });
    }

    private static void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object value = parameters.get(index);
            int parameterIndex = index + 1;
            if (value instanceof String string) {
                statement.setString(parameterIndex, string);
            } else if (value instanceof Boolean bool) {
                statement.setBoolean(parameterIndex, bool);
            } else if (value instanceof Instant instant) {
                statement.setTimestamp(parameterIndex, Timestamp.from(instant));
            } else if (value instanceof Integer integer) {
                statement.setInt(parameterIndex, integer);
            } else if (value instanceof Long longValue) {
                statement.setLong(parameterIndex, longValue);
            } else {
                throw new IllegalArgumentException("Unsupported SQL parameter type: " + value.getClass().getName());
            }
        }
    }

    private static Map<String, String> readStringMap(ResultSet row, String keysColumn, String valuesColumn)
            throws SQLException {
        List<String> keys = readStringArray(row, keysColumn);
        List<String> values = readStringArray(row, valuesColumn);
        if (keys.size() != values.size()) {
            throw new SQLException("Result attribute key/value arrays have different sizes");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < keys.size(); index++) {
            result.put(keys.get(index), values.get(index));
        }
        return Map.copyOf(result);
    }

    private static List<String> readStringArray(ResultSet row, String column) throws SQLException {
        Array sqlArray = row.getArray(column);
        if (sqlArray == null) {
            return List.of();
        }
        try {
            return List.of((String[]) sqlArray.getArray());
        } finally {
            sqlArray.free();
        }
    }

    private static Optional<Instant> optionalInstant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? Optional.empty() : Optional.of(value.toInstant());
    }
}
