package io.signalharvester.analysis.outbox;

import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** PostgreSQL persistence adapter for the Analysis transactional outbox. */
@Singleton
public final class JdbcAnalysisOutboxStore implements AnalysisOutboxStore {

    private static final String INSERT_SQL = """
            INSERT INTO analysis.event_outbox (
                event_id, topic, event_key, payload, traceparent, created_at, publication_attempts
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String CLAIM_SQL = """
            WITH candidates AS (
                SELECT event_id
                  FROM analysis.event_outbox
                 WHERE published_at IS NULL
                   AND (lease_expires_at IS NULL OR lease_expires_at <= ?)
                 ORDER BY created_at, event_id
                 FOR UPDATE SKIP LOCKED
                 LIMIT ?
            )
            UPDATE analysis.event_outbox outbox
               SET lease_token = ?,
                   lease_expires_at = ?,
                   publication_attempts = publication_attempts + 1
              FROM candidates
             WHERE outbox.event_id = candidates.event_id
            RETURNING outbox.event_id,
                      outbox.topic,
                      outbox.event_key,
                      outbox.payload,
                      outbox.traceparent,
                      outbox.created_at,
                      outbox.publication_attempts
            """;
    private static final String MARK_PUBLISHED_SQL = """
            UPDATE analysis.event_outbox
               SET published_at = ?,
                   lease_token = NULL,
                   lease_expires_at = NULL,
                   last_error = NULL
             WHERE event_id = ?
               AND lease_token = ?
               AND published_at IS NULL
            """;
    private static final String MARK_FAILED_SQL = """
            UPDATE analysis.event_outbox
               SET lease_token = NULL,
                   lease_expires_at = ?,
                   last_error = ?
             WHERE event_id = ?
               AND lease_token = ?
               AND published_at IS NULL
            """;

    private final Connection connection;

    public JdbcAnalysisOutboxStore(@Named("default") Connection connection) {
        this.connection = connection;
    }

    @Override
    public void append(AnalysisOutboxEntry entry) {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setString(1, entry.eventId());
            statement.setString(2, entry.topic());
            statement.setString(3, entry.eventKey());
            statement.setBytes(4, entry.payload());
            statement.setString(5, entry.traceparent().orElse(null));
            statement.setTimestamp(6, Timestamp.from(entry.createdAt()));
            statement.setInt(7, entry.publicationAttempts());
            statement.executeUpdate();
        } catch (SQLException | RuntimeException exception) {
            throw new AnalysisOutboxPersistenceException("Failed to append Analysis outbox event", exception);
        }
    }

    @Override
    public List<AnalysisOutboxEntry> claimBatch(
            Instant now, UUID leaseToken, Instant leaseExpiresAt, int limit) {
        try (PreparedStatement statement = connection.prepareStatement(CLAIM_SQL)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setInt(2, limit);
            statement.setObject(3, leaseToken);
            statement.setTimestamp(4, Timestamp.from(leaseExpiresAt));
            try (ResultSet rows = statement.executeQuery()) {
                List<AnalysisOutboxEntry> claimed = new ArrayList<>();
                while (rows.next()) {
                    claimed.add(new AnalysisOutboxEntry(
                            rows.getString("event_id"),
                            rows.getString("topic"),
                            rows.getString("event_key"),
                            rows.getBytes("payload"),
                            java.util.Optional.ofNullable(rows.getString("traceparent")),
                            rows.getTimestamp("created_at").toInstant(),
                            rows.getInt("publication_attempts")));
                }
                return List.copyOf(claimed);
            }
        } catch (SQLException | RuntimeException exception) {
            throw new AnalysisOutboxPersistenceException("Failed to claim Analysis outbox events", exception);
        }
    }

    @Override
    public void markPublished(String eventId, UUID leaseToken, Instant publishedAt) {
        executeLeaseUpdate(MARK_PUBLISHED_SQL, statement -> {
            statement.setTimestamp(1, Timestamp.from(publishedAt));
            statement.setString(2, eventId);
            statement.setObject(3, leaseToken);
        }, "mark Analysis outbox event published");
    }

    @Override
    public void markFailed(String eventId, UUID leaseToken, Instant nextAttemptAt, String failureMessage) {
        executeLeaseUpdate(MARK_FAILED_SQL, statement -> {
            statement.setTimestamp(1, Timestamp.from(nextAttemptAt));
            statement.setString(2, failureMessage);
            statement.setString(3, eventId);
            statement.setObject(4, leaseToken);
        }, "release failed Analysis outbox event");
    }

    private void executeLeaseUpdate(String sql, StatementBinder binder, String operation) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            if (statement.executeUpdate() != 1) {
                throw new AnalysisOutboxPersistenceException(
                        "Failed to " + operation,
                        new IllegalStateException("Outbox lease is no longer owned"));
            }
        } catch (AnalysisOutboxPersistenceException exception) {
            throw exception;
        } catch (SQLException | RuntimeException exception) {
            throw new AnalysisOutboxPersistenceException("Failed to " + operation, exception);
        }
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
