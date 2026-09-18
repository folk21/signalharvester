package io.signalharvester.results.persistence;

import io.signalharvester.common.persistence.SqlResources;
import io.signalharvester.results.application.ResultDetail;
import io.signalharvester.results.application.ResultLiveCriteria;
import io.signalharvester.results.application.ResultLiveUpdate;
import io.signalharvester.results.application.ResultPagePosition;
import io.signalharvester.results.application.ResultQueryCriteria;
import io.signalharvester.results.application.ResultSummary;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Query;
import org.jdbi.v3.stringtemplate4.StringTemplateEngine;

/**
 * Jdbi read adapter for bounded result-feed, detail, and durable live-result queries.
 * Application services retain transaction ownership for all Results reads.
 */
@Singleton
public final class JdbiResultQueryRepository implements ResultQueryRepository, ResultLiveQueryRepository {

    private static final String SQL_PATH = "results/query";
    private static final String FIND_PAGE_SQL = SqlResources.load(SQL_PATH, "find-page");
    private static final String CURRENT_LIVE_CURSOR_SQL = SqlResources.load(SQL_PATH, "current-live-cursor");
    private static final String FIND_LIVE_UPDATES_SQL = SqlResources.load(SQL_PATH, "find-live-updates");
    private static final String FIND_DETAIL_SQL = SqlResources.load(SQL_PATH, "find-detail");
    private static final String FIND_ATTRIBUTES_SQL = SqlResources.load(SQL_PATH, "find-attributes");
    private static final String FIND_TAGS_SQL = SqlResources.load(SQL_PATH, "find-tags");
    private static final StringTemplateEngine STRING_TEMPLATE_ENGINE = new StringTemplateEngine();

    private final Jdbi jdbi;

    public JdbiResultQueryRepository(@Named("default") Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public List<ResultSummary> findPage(
            ResultQueryCriteria criteria, Optional<ResultPagePosition> after, int fetchLimit) {
        return execute("Failed to browse analyzed results", handle -> {
            Query query = handle.createQuery(FIND_PAGE_SQL);
            query.setTemplateEngine(STRING_TEMPLATE_ENGINE);
            configurePageQuery(query, criteria, after);
            query.bind("fetchLimit", fetchLimit);
            return query.map((rows, context) -> mapSummary(rows)).list();
        });
    }

    @Override
    public long currentCursor() {
        return execute("Failed to read current live-result cursor", handle -> handle.createQuery(CURRENT_LIVE_CURSOR_SQL)
                .map((rows, context) -> rows.getLong(1))
                .one());
    }

    @Override
    public List<ResultLiveUpdate> findUpdatesAfter(
            long cursor, long throughCursor, ResultLiveCriteria criteria, int limit) {
        return execute("Failed to read live analyzed-result updates", handle -> {
            Query query = handle.createQuery(FIND_LIVE_UPDATES_SQL);
            query.setTemplateEngine(STRING_TEMPLATE_ENGINE);
            query.bind("cursor", cursor)
                    .bind("throughCursor", throughCursor)
                    .bind("limit", limit);
            configureLiveQuery(query, criteria);
            return query.map((rows, context) -> new ResultLiveUpdate(rows.getLong("live_event_id"), mapSummary(rows)))
                    .list();
        });
    }

    @Override
    public Optional<ResultDetail> find(String monitoringProfileId, String normalizedItemId) {
        return execute("Failed to read analyzed result detail", handle -> {
            Optional<ResultDetailRow> row = handle.createQuery(FIND_DETAIL_SQL)
                    .bind("monitoringProfileId", monitoringProfileId)
                    .bind("normalizedItemId", normalizedItemId)
                    .map((rows, context) -> mapDetailRow(rows))
                    .findOne();
            if (row.isEmpty()) {
                return Optional.empty();
            }

            ResultDetailRow detail = row.orElseThrow();
            Map<String, String> attributes = readAttributes(handle, monitoringProfileId, normalizedItemId);
            List<String> tags = readTags(handle, monitoringProfileId, normalizedItemId);
            return Optional.of(toDetail(detail, attributes, tags));
        });
    }

    private static void configurePageQuery(
            Query query, ResultQueryCriteria criteria, Optional<ResultPagePosition> after) {
        define(query, "hasMonitoringProfileId", criteria.monitoringProfileId());
        define(query, "hasSourceId", criteria.sourceId());
        define(query, "hasInformationCategory", criteria.informationCategory());
        define(query, "hasRelevant", criteria.relevant());
        define(query, "hasClassification", criteria.classification());
        define(query, "hasAnalyzedFrom", criteria.analyzedFrom());
        define(query, "hasAnalyzedTo", criteria.analyzedTo());
        define(query, "hasSearch", criteria.search());
        query.define("hasCursor", after.isPresent());

        bindOptionalString(query, "monitoringProfileId", criteria.monitoringProfileId());
        bindOptionalString(query, "sourceId", criteria.sourceId());
        bindOptionalString(query, "informationCategory", criteria.informationCategory());
        bindOptionalBoolean(query, "relevant", criteria.relevant());
        bindOptionalString(query, "classification", criteria.classification());
        criteria.analyzedFrom().ifPresent(value -> query.bind("analyzedFrom", Timestamp.from(value)));
        criteria.analyzedTo().ifPresent(value -> query.bind("analyzedTo", Timestamp.from(value)));
        bindOptionalString(query, "search", criteria.search());
        after.ifPresent(position -> query
                .bind("afterAnalyzedAt", Timestamp.from(position.analyzedAt()))
                .bind("afterMonitoringProfileId", position.monitoringProfileId())
                .bind("afterNormalizedItemId", position.normalizedItemId()));
    }

    private static void configureLiveQuery(Query query, ResultLiveCriteria criteria) {
        define(query, "hasMonitoringProfileId", criteria.monitoringProfileId());
        define(query, "hasSourceId", criteria.sourceId());
        define(query, "hasInformationCategory", criteria.informationCategory());
        define(query, "hasRelevant", criteria.relevant());
        define(query, "hasClassification", criteria.classification());

        bindOptionalString(query, "monitoringProfileId", criteria.monitoringProfileId());
        bindOptionalString(query, "sourceId", criteria.sourceId());
        bindOptionalString(query, "informationCategory", criteria.informationCategory());
        bindOptionalBoolean(query, "relevant", criteria.relevant());
        bindOptionalString(query, "classification", criteria.classification());
    }

    private static void define(Query query, String name, Optional<?> value) {
        query.define(name, value.isPresent());
    }

    private static void bindOptionalString(Query query, String name, Optional<String> value) {
        value.ifPresent(item -> query.bind(name, item));
    }

    private static void bindOptionalBoolean(Query query, String name, Optional<Boolean> value) {
        value.ifPresent(item -> query.bind(name, item));
    }

    private static Map<String, String> readAttributes(
            Handle handle, String monitoringProfileId, String normalizedItemId) {
        Map<String, String> attributes = new LinkedHashMap<>();
        handle.createQuery(FIND_ATTRIBUTES_SQL)
                .bind("monitoringProfileId", monitoringProfileId)
                .bind("normalizedItemId", normalizedItemId)
                .map((rows, context) -> Map.entry(
                        rows.getString("attribute_key"),
                        rows.getString("attribute_value")))
                .forEach(attribute -> attributes.put(attribute.getKey(), attribute.getValue()));
        return Map.copyOf(attributes);
    }

    private static List<String> readTags(
            Handle handle, String monitoringProfileId, String normalizedItemId) {
        return handle.createQuery(FIND_TAGS_SQL)
                .bind("monitoringProfileId", monitoringProfileId)
                .bind("normalizedItemId", normalizedItemId)
                .map((rows, context) -> rows.getString("tag"))
                .list();
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

    private static ResultDetailRow mapDetailRow(ResultSet row) throws SQLException {
        return new ResultDetailRow(
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
                row.getBoolean("relevant"),
                row.getString("classification"),
                row.getInt("score"),
                row.getString("explanation"),
                row.getString("analyzer"),
                optionalInstant(row, "published_at"),
                row.getTimestamp("analyzed_at").toInstant(),
                row.getString("correlation_id"),
                Optional.ofNullable(row.getString("traceparent")));
    }

    private static ResultDetail toDetail(
            ResultDetailRow row, Map<String, String> attributes, List<String> tags) {
        return new ResultDetail(
                row.monitoringProfileId(),
                row.normalizedItemId(),
                row.analysisEventId(),
                row.sourceEventId(),
                row.rawItemId(),
                row.sourceId(),
                row.informationCategory(),
                row.externalId(),
                row.title(),
                row.url(),
                row.normalizedContent(),
                row.contentType(),
                attributes,
                row.relevant(),
                row.classification(),
                row.score(),
                tags,
                row.explanation(),
                row.analyzer(),
                row.publishedAt(),
                row.analyzedAt(),
                row.correlationId(),
                row.traceparent());
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

    private <T> T execute(String message, HandleFunction<T> operation) {
        try {
            return jdbi.withHandle(handle -> {
                requireActiveTransaction(handle);
                return operation.apply(handle);
            });
        } catch (ResultsPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResultsPersistenceException(message, exception);
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

    private record ResultDetailRow(
            String monitoringProfileId,
            String normalizedItemId,
            String analysisEventId,
            String sourceEventId,
            String rawItemId,
            String sourceId,
            String informationCategory,
            Optional<String> externalId,
            Optional<String> title,
            String url,
            String normalizedContent,
            String contentType,
            boolean relevant,
            String classification,
            int score,
            String explanation,
            String analyzer,
            Optional<Instant> publishedAt,
            Instant analyzedAt,
            String correlationId,
            Optional<String> traceparent) {
    }
}
