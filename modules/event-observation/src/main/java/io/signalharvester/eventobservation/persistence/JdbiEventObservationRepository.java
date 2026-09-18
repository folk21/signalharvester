package io.signalharvester.eventobservation.persistence;

import io.signalharvester.common.persistence.SqlResources;
import io.signalharvester.eventobservation.application.EventObservationCriteria;
import io.signalharvester.eventobservation.model.ObservedEvent;
import io.signalharvester.eventobservation.model.ObservedEventInput;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Query;
import org.jdbi.v3.core.statement.Update;

/**
 * Jdbi adapter for idempotent bounded Event Observation persistence and reads.
 * Application services retain transaction ownership for recording, retention, and query operations.
 */
@Singleton
public final class JdbiEventObservationRepository implements EventObservationRepository {

    private static final String SQL_PATH = "event-observation";
    private static final String INSERT_SQL = SqlResources.load(SQL_PATH, "insert");
    private static final String PRUNE_SQL = SqlResources.load(SQL_PATH, "prune");
    private static final String FIND_RECENT_SQL = SqlResources.load(SQL_PATH, "find-recent");
    private static final String CURRENT_CURSOR_SQL = SqlResources.load(SQL_PATH, "current-cursor");
    private static final String FIND_AFTER_SQL = SqlResources.load(SQL_PATH, "find-after");

    private final Jdbi jdbi;

    public JdbiEventObservationRepository(@Named("default") Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public void insert(ObservedEventInput event) {
        executeVoid("Failed to record observed event", handle -> {
            Update update = handle.createUpdate(INSERT_SQL)
                    .bind("eventId", event.eventId())
                    .bind("eventType", event.eventType())
                    .bind("occurredAt", Timestamp.from(event.occurredAt()))
                    .bind("observedAt", Timestamp.from(event.observedAt()))
                    .bind("correlationId", event.correlationId())
                    .bind("producer", event.producer())
                    .bind("schemaVersion", event.schemaVersion())
                    .bind("kafkaTopic", event.kafkaTopic())
                    .bind("kafkaPartition", event.kafkaPartition())
                    .bind("kafkaOffset", event.kafkaOffset())
                    .bind("kafkaKey", event.kafkaKey())
                    .bind("payloadType", event.payloadType());
            bindOptionalString(update, "traceparent", event.traceparent());
            bindOptionalString(update, "sourceEventId", event.sourceEventId());
            bindOptionalString(update, "rawItemId", event.rawItemId());
            bindOptionalString(update, "normalizedItemId", event.normalizedItemId());
            bindOptionalString(update, "sourceId", event.sourceId());
            bindOptionalString(update, "monitoringProfileId", event.monitoringProfileId());
            bindOptionalString(update, "informationCategory", event.informationCategory());
            bindOptionalString(update, "externalId", event.externalId());
            bindOptionalString(update, "title", event.title());
            bindOptionalString(update, "url", event.url());
            bindOptionalString(update, "contentType", event.contentType());
            update.bindByType("relevant", event.relevant().orElse(null), Boolean.class);
            bindOptionalString(update, "classification", event.classification());
            update.bindByType(
                    "score",
                    event.score().isPresent() ? event.score().getAsInt() : null,
                    Integer.class);
            bindOptionalString(update, "analyzer", event.analyzer());
            bindOptionalString(update, "reasonCode", event.reasonCode());
            bindOptionalString(update, "explanation", event.explanation());
            update.execute();
        });
    }

    @Override
    public void prune(Instant cutoff, int maxEvents) {
        executeVoid("Failed to prune observed events", handle -> handle.createUpdate(PRUNE_SQL)
                .bind("cutoff", Timestamp.from(cutoff))
                .bind("maxEvents", maxEvents)
                .execute());
    }

    @Override
    public List<ObservedEvent> findRecent(EventObservationCriteria criteria, int limit) {
        return execute("Failed to query observed events", handle -> {
            Query query = handle.createQuery(FIND_RECENT_SQL)
                    .bind("limit", limit);
            bindCriteria(query, criteria);
            return query.map((rows, context) -> map(rows)).list();
        });
    }

    @Override
    public long currentCursor() {
        return execute("Failed to read event-observation cursor", handle -> handle.createQuery(CURRENT_CURSOR_SQL)
                .map((rows, context) -> rows.getLong(1))
                .one());
    }

    @Override
    public List<ObservedEvent> findAfter(
            long cursor, long throughCursor, EventObservationCriteria criteria, int limit) {
        return execute("Failed to query observed events", handle -> {
            Query query = handle.createQuery(FIND_AFTER_SQL)
                    .bind("cursor", cursor)
                    .bind("throughCursor", throughCursor)
                    .bind("limit", limit);
            bindCriteria(query, criteria);
            return query.map((rows, context) -> map(rows)).list();
        });
    }

    private static void bindCriteria(Query query, EventObservationCriteria criteria) {
        query.bindByType("eventType", criteria.eventType().orElse(null), String.class)
                .bindByType("producer", criteria.producer().orElse(null), String.class)
                .bindByType("topic", criteria.topic().orElse(null), String.class)
                .bindByType("correlationId", criteria.correlationId().orElse(null), String.class)
                .bindByType("collectionRunId", criteria.collectionRunId().orElse(null), String.class)
                .bindByType("itemId", criteria.itemId().orElse(null), String.class)
                .bindByType(
                        "tracePattern",
                        criteria.traceId().map(value -> "00-" + value + "-%").orElse(null),
                        String.class);
    }

    private static void bindOptionalString(Update update, String name, Optional<String> value) {
        update.bindByType(name, value.orElse(null), String.class);
    }

    private static ObservedEvent map(ResultSet row) throws SQLException {
        int score = row.getInt("score");
        OptionalInt optionalScore = row.wasNull() ? OptionalInt.empty() : OptionalInt.of(score);
        return new ObservedEvent(
                row.getLong("observation_id"),
                row.getString("event_id"),
                row.getString("event_type"),
                row.getTimestamp("occurred_at").toInstant(),
                row.getTimestamp("observed_at").toInstant(),
                row.getString("correlation_id"),
                optional(row.getString("traceparent")),
                row.getString("producer"),
                row.getString("schema_version"),
                row.getString("kafka_topic"),
                row.getInt("kafka_partition"),
                row.getLong("kafka_offset"),
                row.getString("kafka_key"),
                row.getString("payload_type"),
                optional(row.getString("source_event_id")),
                optional(row.getString("raw_item_id")),
                optional(row.getString("normalized_item_id")),
                optional(row.getString("source_id")),
                optional(row.getString("monitoring_profile_id")),
                optional(row.getString("information_category")),
                optional(row.getString("external_id")),
                optional(row.getString("title")),
                optional(row.getString("url")),
                optional(row.getString("content_type")),
                optionalBoolean(row, "relevant"),
                optional(row.getString("classification")),
                optionalScore,
                optional(row.getString("analyzer")),
                optional(row.getString("reason_code")),
                optional(row.getString("explanation")));
    }

    private static Optional<Boolean> optionalBoolean(ResultSet row, String column) throws SQLException {
        boolean value = row.getBoolean(column);
        return row.wasNull() ? Optional.empty() : Optional.of(value);
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
        } catch (EventObservationPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new EventObservationPersistenceException(message, exception);
        }
    }

    private void executeVoid(String message, HandleConsumer operation) {
        try {
            jdbi.useHandle(handle -> {
                requireActiveTransaction(handle);
                operation.accept(handle);
            });
        } catch (EventObservationPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new EventObservationPersistenceException(message, exception);
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
}
