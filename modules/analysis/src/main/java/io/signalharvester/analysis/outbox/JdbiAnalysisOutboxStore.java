package io.signalharvester.analysis.outbox;

import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Update;

/** Jdbi persistence adapter for the Analysis transactional outbox. */
@Singleton
public final class JdbiAnalysisOutboxStore implements AnalysisOutboxStore {

    private static final String INSERT_SQL = AnalysisOutboxSql.get("insert");
    private static final String CLAIM_SQL = AnalysisOutboxSql.get("claim");
    private static final String MARK_PUBLISHED_SQL = AnalysisOutboxSql.get("mark-published");
    private static final String MARK_FAILED_SQL = AnalysisOutboxSql.get("mark-failed");

    private final Jdbi jdbi;

    public JdbiAnalysisOutboxStore(@Named("default") Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public void append(AnalysisOutboxEntry entry) {
        executeVoid("Failed to append Analysis outbox event", handle -> {
            Update update = handle.createUpdate(INSERT_SQL)
                    .bind("eventId", entry.eventId())
                    .bind("topic", entry.topic())
                    .bind("eventKey", entry.eventKey())
                    .bind("payload", entry.payload())
                    .bind("createdAt", Timestamp.from(entry.createdAt()))
                    .bind("publicationAttempts", entry.publicationAttempts());
            entry.traceparent().ifPresentOrElse(
                    value -> update.bind("traceparent", value),
                    () -> update.bindNull("traceparent", Types.VARCHAR));
            update.execute();
        });
    }

    @Override
    public List<AnalysisOutboxEntry> claimBatch(
            Instant now, UUID leaseToken, Instant leaseExpiresAt, int limit) {
        return execute("Failed to claim Analysis outbox events", handle -> handle.createQuery(CLAIM_SQL)
                .bind("now", Timestamp.from(now))
                .bind("limit", limit)
                .bind("leaseToken", leaseToken)
                .bind("leaseExpiresAt", Timestamp.from(leaseExpiresAt))
                .map((rows, context) -> mapEntry(rows))
                .list());
    }

    @Override
    public void markPublished(String eventId, UUID leaseToken, Instant publishedAt) {
        executeLeaseUpdate(
                "mark Analysis outbox event published",
                handle -> handle.createUpdate(MARK_PUBLISHED_SQL)
                        .bind("publishedAt", Timestamp.from(publishedAt))
                        .bind("eventId", eventId)
                        .bind("leaseToken", leaseToken)
                        .execute());
    }

    @Override
    public void markFailed(String eventId, UUID leaseToken, Instant nextAttemptAt, String failureMessage) {
        executeLeaseUpdate(
                "release failed Analysis outbox event",
                handle -> handle.createUpdate(MARK_FAILED_SQL)
                        .bind("nextAttemptAt", Timestamp.from(nextAttemptAt))
                        .bind("failureMessage", failureMessage)
                        .bind("eventId", eventId)
                        .bind("leaseToken", leaseToken)
                        .execute());
    }

    private static AnalysisOutboxEntry mapEntry(ResultSet rows) throws SQLException {
        return new AnalysisOutboxEntry(
                rows.getString("event_id"),
                rows.getString("topic"),
                rows.getString("event_key"),
                rows.getBytes("payload"),
                Optional.ofNullable(rows.getString("traceparent")),
                rows.getTimestamp("created_at").toInstant(),
                rows.getInt("publication_attempts"));
    }

    private void executeLeaseUpdate(String operation, HandleUpdate operationUpdate) {
        executeVoid("Failed to " + operation, handle -> {
            if (operationUpdate.execute(handle) != 1) {
                throw new AnalysisOutboxPersistenceException(
                        "Failed to " + operation,
                        new IllegalStateException("Outbox lease is no longer owned"));
            }
        });
    }

    private <T> T execute(String message, HandleFunction<T> operation) {
        try {
            return jdbi.withHandle(handle -> {
                requireActiveTransaction(handle);
                return operation.apply(handle);
            });
        } catch (AnalysisOutboxPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AnalysisOutboxPersistenceException(message, exception);
        }
    }

    private void executeVoid(String message, HandleConsumer operation) {
        try {
            jdbi.useHandle(handle -> {
                requireActiveTransaction(handle);
                operation.accept(handle);
            });
        } catch (AnalysisOutboxPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AnalysisOutboxPersistenceException(message, exception);
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

    @FunctionalInterface
    private interface HandleUpdate {
        int execute(Handle handle);
    }
}
