package io.signalharvester.analysis.persistence;

import io.signalharvester.analysis.model.NormalizedContentItem;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Update;

/**
 * Jdbi adapter that enforces one accepted logical item per monitoring-profile deduplication scope.
 * Transaction boundaries remain owned by Analysis application use cases.
 */
@Singleton
public final class JdbiDeduplicationClaimRepository implements DeduplicationClaimRepository {

    private static final String TRY_CLAIM_SQL = AnalysisPersistenceSql.deduplication("try-claim");
    private static final String RECORD_DUPLICATE_SQL = AnalysisPersistenceSql.deduplication("record-duplicate");

    private final Jdbi jdbi;

    public JdbiDeduplicationClaimRepository(@Named("default") Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public boolean tryClaim(NormalizedContentItem item, Instant seenAt) {
        return execute("Failed to claim normalized item", handle -> {
            Update update = handle.createUpdate(TRY_CLAIM_SQL)
                    .bind("monitoringProfileId", item.monitoringProfileId())
                    .bind("normalizedItemId", item.normalizedItemId())
                    .bind("sourceId", item.sourceId())
                    .bind("sourceUrl", item.url().toString())
                    .bind("firstRawItemId", item.rawItemId())
                    .bind("firstSourceEventId", item.sourceEventId())
                    .bind("firstSeenAt", Timestamp.from(seenAt))
                    .bind("lastRawItemId", item.rawItemId())
                    .bind("lastSourceEventId", item.sourceEventId())
                    .bind("lastSeenAt", Timestamp.from(seenAt));
            item.externalId().ifPresentOrElse(
                    value -> update.bind("externalId", value),
                    () -> update.bindNull("externalId", Types.VARCHAR));
            return update.execute() == 1;
        });
    }

    @Override
    public void recordDuplicate(NormalizedContentItem item, Instant seenAt) {
        executeVoid("Failed to record duplicate discovery", handle -> {
            int updated = handle.createUpdate(RECORD_DUPLICATE_SQL)
                    .bind("lastRawItemId", item.rawItemId())
                    .bind("lastSourceEventId", item.sourceEventId())
                    .bind("lastSeenAt", Timestamp.from(seenAt))
                    .bind("monitoringProfileId", item.monitoringProfileId())
                    .bind("normalizedItemId", item.normalizedItemId())
                    .execute();
            if (updated != 1) {
                throw new AnalysisPersistenceException(
                        "Normalized item claim disappeared while recording duplicate",
                        new IllegalStateException("No deduplication claim found"));
            }
        });
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

    private void executeVoid(String message, HandleConsumer operation) {
        try {
            jdbi.useHandle(handle -> {
                requireActiveTransaction(handle);
                operation.accept(handle);
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

    @FunctionalInterface
    private interface HandleConsumer {
        void accept(Handle handle);
    }
}
