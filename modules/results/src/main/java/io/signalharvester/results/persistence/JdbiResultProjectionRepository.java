package io.signalharvester.results.persistence;

import io.signalharvester.common.persistence.SqlResources;
import io.signalharvester.results.model.AnalyzedResult;
import io.signalharvester.results.model.RejectedResult;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Timestamp;
import java.util.Map;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.PreparedBatch;
import org.jdbi.v3.core.statement.Update;

/**
 * Jdbi adapter for Results-owned materialized analysis outcomes.
 * Application services retain transaction ownership for analyzed/rejected projection writes.
 */
@Singleton
public final class JdbiResultProjectionRepository implements ResultProjectionRepository {

    private static final String SQL_PATH = "results/projection";
    private static final String UPSERT_ANALYZED_SQL = SqlResources.load(SQL_PATH, "upsert-analyzed");
    private static final String DELETE_ATTRIBUTES_SQL = SqlResources.load(SQL_PATH, "delete-attributes");
    private static final String INSERT_ATTRIBUTE_SQL = SqlResources.load(SQL_PATH, "insert-attribute");
    private static final String DELETE_TAGS_SQL = SqlResources.load(SQL_PATH, "delete-tags");
    private static final String INSERT_TAG_SQL = SqlResources.load(SQL_PATH, "insert-tag");
    private static final String UPSERT_LIVE_CURSOR_SQL = SqlResources.load(SQL_PATH, "upsert-live-cursor");
    private static final String UPSERT_REJECTED_SQL = SqlResources.load(SQL_PATH, "upsert-rejected");

    private final Jdbi jdbi;

    public JdbiResultProjectionRepository(@Named("default") Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public void upsertAnalyzed(AnalyzedResult result) {
        executeVoid("Failed to persist analyzed result projection", handle -> {
            upsertAnalyzedRow(handle, result);
            replaceAttributes(handle, result);
            replaceTags(handle, result);
            upsertLiveCursor(handle, result);
        });
    }

    @Override
    public void upsertRejected(RejectedResult result) {
        executeVoid("Failed to persist rejected result projection", handle -> {
            Update update = handle.createUpdate(UPSERT_REJECTED_SQL)
                    .bind("sourceEventId", result.sourceEventId())
                    .bind("analysisEventId", result.analysisEventId())
                    .bind("rawItemId", result.rawItemId())
                    .bind("sourceId", result.sourceId())
                    .bind("monitoringProfileId", result.monitoringProfileId())
                    .bind("informationCategory", result.informationCategory())
                    .bind("reasonCode", result.reasonCode())
                    .bind("explanation", result.explanation())
                    .bind("rejectedAt", Timestamp.from(result.rejectedAt()))
                    .bind("correlationId", result.correlationId());
            update.bindByType("normalizedItemId", result.normalizedItemId().orElse(null), String.class)
                    .bindByType("traceparent", result.traceparent().orElse(null), String.class)
                    .execute();
        });
    }

    private static void upsertAnalyzedRow(Handle handle, AnalyzedResult result) {
        Update update = handle.createUpdate(UPSERT_ANALYZED_SQL)
                .bind("monitoringProfileId", result.monitoringProfileId())
                .bind("normalizedItemId", result.normalizedItemId())
                .bind("analysisEventId", result.analysisEventId())
                .bind("sourceEventId", result.sourceEventId())
                .bind("rawItemId", result.rawItemId())
                .bind("sourceId", result.sourceId())
                .bind("informationCategory", result.informationCategory())
                .bind("url", result.url())
                .bind("normalizedContent", result.normalizedContent())
                .bind("contentType", result.contentType())
                .bind("relevant", result.relevant())
                .bind("classification", result.classification())
                .bind("score", result.score())
                .bind("explanation", result.explanation())
                .bind("analyzer", result.analyzer())
                .bind("analyzedAt", Timestamp.from(result.analyzedAt()))
                .bind("correlationId", result.correlationId());
        update.bindByType("externalId", result.externalId().orElse(null), String.class)
                .bindByType("title", result.title().orElse(null), String.class)
                .bindByType(
                        "publishedAt",
                        result.publishedAt().map(Timestamp::from).orElse(null),
                        Timestamp.class)
                .bindByType("traceparent", result.traceparent().orElse(null), String.class)
                .execute();
    }

    private static void upsertLiveCursor(Handle handle, AnalyzedResult result) {
        handle.createUpdate(UPSERT_LIVE_CURSOR_SQL)
                .bind("monitoringProfileId", result.monitoringProfileId())
                .bind("normalizedItemId", result.normalizedItemId())
                .bind("analysisEventId", result.analysisEventId())
                .execute();
    }

    private static void replaceAttributes(Handle handle, AnalyzedResult result) {
        deleteByIdentity(handle, DELETE_ATTRIBUTES_SQL, result);
        if (result.attributes().isEmpty()) {
            return;
        }

        PreparedBatch batch = handle.prepareBatch(INSERT_ATTRIBUTE_SQL);
        for (Map.Entry<String, String> attribute : result.attributes().entrySet()) {
            batch.bind("monitoringProfileId", result.monitoringProfileId())
                    .bind("normalizedItemId", result.normalizedItemId())
                    .bind("attributeKey", attribute.getKey())
                    .bind("attributeValue", attribute.getValue())
                    .add();
        }
        batch.execute();
    }

    private static void replaceTags(Handle handle, AnalyzedResult result) {
        deleteByIdentity(handle, DELETE_TAGS_SQL, result);
        if (result.tags().isEmpty()) {
            return;
        }

        PreparedBatch batch = handle.prepareBatch(INSERT_TAG_SQL);
        for (int index = 0; index < result.tags().size(); index++) {
            batch.bind("monitoringProfileId", result.monitoringProfileId())
                    .bind("normalizedItemId", result.normalizedItemId())
                    .bind("tagOrdinal", index)
                    .bind("tag", result.tags().get(index))
                    .add();
        }
        batch.execute();
    }

    private static void deleteByIdentity(Handle handle, String sql, AnalyzedResult result) {
        handle.createUpdate(sql)
                .bind("monitoringProfileId", result.monitoringProfileId())
                .bind("normalizedItemId", result.normalizedItemId())
                .execute();
    }

    private void executeVoid(String message, HandleConsumer operation) {
        try {
            jdbi.useHandle(handle -> {
                requireActiveTransaction(handle);
                operation.accept(handle);
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
    private interface HandleConsumer {
        void accept(Handle handle);
    }
}
