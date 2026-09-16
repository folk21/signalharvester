package io.signalharvester.eventobservation.persistence;

import io.signalharvester.eventobservation.application.EventObservationCriteria;
import io.signalharvester.eventobservation.model.ObservedEvent;
import io.signalharvester.eventobservation.model.ObservedEventInput;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** PostgreSQL adapter for idempotent bounded event-observation persistence and reads. */
@Singleton
public final class JdbcEventObservationRepository implements EventObservationRepository {

    private static final String INSERT_SQL = """
            INSERT INTO event_observation.observed_events (
                event_id, event_type, occurred_at, observed_at, correlation_id, traceparent,
                producer, schema_version, kafka_topic, kafka_partition, kafka_offset, kafka_key,
                payload_type, source_event_id, raw_item_id, normalized_item_id, source_id,
                monitoring_profile_id, information_category, external_id, title, url, content_type,
                relevant, classification, score, analyzer, reason_code, explanation
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;
    private static final String BASE_SELECT = """
            SELECT observation_id, event_id, event_type, occurred_at, observed_at, correlation_id,
                   traceparent, producer, schema_version, kafka_topic, kafka_partition, kafka_offset,
                   kafka_key, payload_type, source_event_id, raw_item_id, normalized_item_id, source_id,
                   monitoring_profile_id, information_category, external_id, title, url, content_type,
                   relevant, classification, score, analyzer, reason_code, explanation
              FROM event_observation.observed_events
             WHERE 1 = 1
            """;
    private static final String CURRENT_CURSOR_SQL = """
            SELECT COALESCE(MAX(observation_id), 0) FROM event_observation.observed_events
            """;
    private static final String PRUNE_SQL = """
            DELETE FROM event_observation.observed_events
             WHERE observed_at < ?
                OR observation_id NOT IN (
                    SELECT observation_id
                      FROM event_observation.observed_events
                     WHERE observed_at >= ?
                     ORDER BY observation_id DESC
                     LIMIT ?
                )
            """;

    private final Connection connection;

    public JdbcEventObservationRepository(@Named("default") Connection connection) {
        this.connection = connection;
    }

    @Override
    public void insert(ObservedEventInput event) {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            int index = 1;
            statement.setString(index++, event.eventId());
            statement.setString(index++, event.eventType());
            statement.setTimestamp(index++, Timestamp.from(event.occurredAt()));
            statement.setTimestamp(index++, Timestamp.from(event.observedAt()));
            statement.setString(index++, event.correlationId());
            setNullableString(statement, index++, event.traceparent().orElse(null));
            statement.setString(index++, event.producer());
            statement.setString(index++, event.schemaVersion());
            statement.setString(index++, event.kafkaTopic());
            statement.setInt(index++, event.kafkaPartition());
            statement.setLong(index++, event.kafkaOffset());
            statement.setString(index++, event.kafkaKey());
            statement.setString(index++, event.payloadType());
            setNullableString(statement, index++, event.sourceEventId().orElse(null));
            setNullableString(statement, index++, event.rawItemId().orElse(null));
            setNullableString(statement, index++, event.normalizedItemId().orElse(null));
            setNullableString(statement, index++, event.sourceId().orElse(null));
            setNullableString(statement, index++, event.monitoringProfileId().orElse(null));
            setNullableString(statement, index++, event.informationCategory().orElse(null));
            setNullableString(statement, index++, event.externalId().orElse(null));
            setNullableString(statement, index++, event.title().orElse(null));
            setNullableString(statement, index++, event.url().orElse(null));
            setNullableString(statement, index++, event.contentType().orElse(null));
            setNullableBoolean(statement, index++, event.relevant().orElse(null));
            setNullableString(statement, index++, event.classification().orElse(null));
            if (event.score().isPresent()) {
                statement.setInt(index++, event.score().getAsInt());
            } else {
                statement.setNull(index++, Types.INTEGER);
            }
            setNullableString(statement, index++, event.analyzer().orElse(null));
            setNullableString(statement, index++, event.reasonCode().orElse(null));
            setNullableString(statement, index, event.explanation().orElse(null));
            statement.executeUpdate();
        } catch (SQLException | RuntimeException exception) {
            throw new EventObservationPersistenceException("Failed to record observed event", exception);
        }
    }

    @Override
    public void prune(Instant cutoff, int maxEvents) {
        try (PreparedStatement statement = connection.prepareStatement(PRUNE_SQL)) {
            statement.setTimestamp(1, Timestamp.from(cutoff));
            statement.setTimestamp(2, Timestamp.from(cutoff));
            statement.setInt(3, maxEvents);
            statement.executeUpdate();
        } catch (SQLException | RuntimeException exception) {
            throw new EventObservationPersistenceException("Failed to prune observed events", exception);
        }
    }

    @Override
    public List<ObservedEvent> findRecent(EventObservationCriteria criteria, int limit) {
        StringBuilder sql = new StringBuilder(BASE_SELECT);
        List<Object> parameters = new ArrayList<>();
        appendCriteria(sql, parameters, criteria);
        sql.append(" ORDER BY occurred_at DESC, observation_id DESC LIMIT ?");
        parameters.add(limit);
        return query(sql.toString(), parameters);
    }

    @Override
    public long currentCursor() {
        try (PreparedStatement statement = connection.prepareStatement(CURRENT_CURSOR_SQL);
                ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("Event-observation cursor query returned no row");
            }
            return rows.getLong(1);
        } catch (SQLException | RuntimeException exception) {
            throw new EventObservationPersistenceException("Failed to read event-observation cursor", exception);
        }
    }

    @Override
    public List<ObservedEvent> findAfter(
            long cursor, long throughCursor, EventObservationCriteria criteria, int limit) {
        StringBuilder sql = new StringBuilder(BASE_SELECT);
        List<Object> parameters = new ArrayList<>();
        sql.append(" AND observation_id > ? AND observation_id <= ?\n");
        parameters.add(cursor);
        parameters.add(throughCursor);
        appendCriteria(sql, parameters, criteria);
        sql.append(" ORDER BY observation_id ASC LIMIT ?");
        parameters.add(limit);
        return query(sql.toString(), parameters);
    }

    private List<ObservedEvent> query(String sql, List<Object> parameters) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                List<ObservedEvent> events = new ArrayList<>();
                while (rows.next()) {
                    events.add(map(rows));
                }
                return List.copyOf(events);
            }
        } catch (SQLException | RuntimeException exception) {
            throw new EventObservationPersistenceException("Failed to query observed events", exception);
        }
    }

    private static void appendCriteria(
            StringBuilder sql, List<Object> parameters, EventObservationCriteria criteria) {
        appendText(sql, parameters, "event_type", criteria.eventType());
        appendText(sql, parameters, "producer", criteria.producer());
        appendText(sql, parameters, "kafka_topic", criteria.topic());
        appendText(sql, parameters, "correlation_id", criteria.correlationId());
        appendText(sql, parameters, "correlation_id", criteria.collectionRunId());
        criteria.itemId().ifPresent(value -> {
            sql.append(" AND (raw_item_id = ? OR normalized_item_id = ?)\n");
            parameters.add(value);
            parameters.add(value);
        });
        criteria.traceId().ifPresent(value -> {
            sql.append(" AND traceparent LIKE ?\n");
            parameters.add("00-" + value + "-%");
        });
    }

    private static void appendText(
            StringBuilder sql, List<Object> parameters, String column, Optional<String> value) {
        value.ifPresent(filter -> {
            sql.append(" AND ").append(column).append(" = ?\n");
            parameters.add(filter);
        });
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

    private static void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object value = parameters.get(index);
            if (value instanceof Integer integer) {
                statement.setInt(index + 1, integer);
            } else if (value instanceof Long longValue) {
                statement.setLong(index + 1, longValue);
            } else {
                statement.setString(index + 1, value.toString());
            }
        }
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void setNullableBoolean(PreparedStatement statement, int index, Boolean value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BOOLEAN);
        } else {
            statement.setBoolean(index, value);
        }
    }
}
