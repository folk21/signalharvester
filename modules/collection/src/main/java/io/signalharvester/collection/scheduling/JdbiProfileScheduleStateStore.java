package io.signalharvester.collection.scheduling;

import io.signalharvester.common.persistence.SqlResources;

import io.signalharvester.configuration.api.MonitoringProfileId;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/** Jdbi adapter for collection-owned monitoring-profile schedule state. */
@Singleton
public final class JdbiProfileScheduleStateStore implements ProfileScheduleStateStore {

    private static final String SQL_PATH = "collection/schedule";
    private static final String INSERT_STATE_SQL = SqlResources.load(SQL_PATH, "insert-state");
    private static final String RESCHEDULE_INTERVAL_SQL = SqlResources.load(SQL_PATH, "reschedule-interval");
    private static final String CLAIM_SQL = SqlResources.load(SQL_PATH, "claim");
    private static final String RENEW_SQL = SqlResources.load(SQL_PATH, "renew");
    private static final String RELEASE_SQL = SqlResources.load(SQL_PATH, "release");
    private static final String COMPLETE_SQL = SqlResources.load(SQL_PATH, "complete");

    private final Jdbi jdbi;

    public JdbiProfileScheduleStateStore(@Named("default") Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public Optional<ProfileScheduleLease> claimIfDue(
            MonitoringProfileId profileId,
            int intervalMinutes,
            Instant now,
            Duration leaseDuration) {
        return execute("Failed to claim monitoring-profile schedule", handle -> {
            Instant nextDue = now.plus(Duration.ofMinutes(intervalMinutes));
            if (insertState(handle, profileId, intervalMinutes, nextDue, now)) {
                return Optional.empty();
            }
            if (rescheduleChangedInterval(handle, profileId, intervalMinutes, nextDue, now)) {
                return Optional.empty();
            }

            UUID token = UUID.randomUUID();
            Instant leaseUntil = now.plus(leaseDuration);
            return handle.createQuery(CLAIM_SQL)
                    .bind("leaseToken", token)
                    .bind("leaseUntil", Timestamp.from(leaseUntil))
                    .bind("updatedAt", Timestamp.from(now))
                    .bind("monitoringProfileId", profileId.value())
                    .bind("intervalMinutes", intervalMinutes)
                    .bind("now", Timestamp.from(now))
                    .map((rows, context) -> rows.getObject("monitoring_profile_id", UUID.class))
                    .findFirst()
                    .map(ignored -> new ProfileScheduleLease(profileId, token, intervalMinutes));
        });
    }

    @Override
    public boolean renew(ProfileScheduleLease lease, Instant now, Duration leaseDuration) {
        return execute("Failed to renew monitoring-profile schedule lease", handle ->
                handle.createUpdate(RENEW_SQL)
                        .bind("leaseUntil", Timestamp.from(now.plus(leaseDuration)))
                        .bind("updatedAt", Timestamp.from(now))
                        .bind("monitoringProfileId", lease.profileId().value())
                        .bind("leaseToken", lease.token())
                        .execute() == 1);
    }

    @Override
    public boolean release(ProfileScheduleLease lease, Instant releasedAt) {
        return execute("Failed to release monitoring-profile schedule lease", handle ->
                handle.createUpdate(RELEASE_SQL)
                        .bind("updatedAt", Timestamp.from(releasedAt))
                        .bind("monitoringProfileId", lease.profileId().value())
                        .bind("leaseToken", lease.token())
                        .execute() == 1);
    }

    @Override
    public boolean complete(ProfileScheduleLease lease, Instant completedAt) {
        return execute("Failed to complete monitoring-profile schedule lease", handle ->
                handle.createUpdate(COMPLETE_SQL)
                        .bind("nextDueAt", Timestamp.from(
                                completedAt.plus(Duration.ofMinutes(lease.intervalMinutes()))))
                        .bind("updatedAt", Timestamp.from(completedAt))
                        .bind("monitoringProfileId", lease.profileId().value())
                        .bind("leaseToken", lease.token())
                        .execute() == 1);
    }

    private static boolean insertState(
            Handle handle,
            MonitoringProfileId profileId,
            int intervalMinutes,
            Instant nextDue,
            Instant now) {
        return handle.createUpdate(INSERT_STATE_SQL)
                .bind("monitoringProfileId", profileId.value())
                .bind("intervalMinutes", intervalMinutes)
                .bind("nextDueAt", Timestamp.from(nextDue))
                .bind("updatedAt", Timestamp.from(now))
                .execute() == 1;
    }

    private static boolean rescheduleChangedInterval(
            Handle handle,
            MonitoringProfileId profileId,
            int intervalMinutes,
            Instant nextDue,
            Instant now) {
        return handle.createUpdate(RESCHEDULE_INTERVAL_SQL)
                .bind("intervalMinutes", intervalMinutes)
                .bind("nextDueAt", Timestamp.from(nextDue))
                .bind("updatedAt", Timestamp.from(now))
                .bind("monitoringProfileId", profileId.value())
                .bind("now", Timestamp.from(now))
                .execute() == 1;
    }

    private <T> T execute(String message, HandleFunction<T> operation) {
        try {
            return jdbi.withHandle(handle -> {
                requireActiveTransaction(handle);
                return operation.apply(handle);
            });
        } catch (ProfileSchedulePersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ProfileSchedulePersistenceException(message, exception);
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
