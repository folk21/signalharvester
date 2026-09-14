package io.signalharvester.collection.scheduling;

import io.signalharvester.configuration.api.MonitoringProfileId;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL adapter for collection-owned monitoring-profile schedule state. */
@Singleton
public final class JdbcProfileScheduleStateStore implements ProfileScheduleStateStore {
    private static final String INSERT_STATE_SQL = """
            INSERT INTO collection.monitoring_profile_schedule_state (
                monitoring_profile_id, interval_minutes, next_due_at, updated_at
            ) VALUES (?, ?, ?, ?)
            ON CONFLICT (monitoring_profile_id) DO NOTHING
            """;
    private static final String RESCHEDULE_INTERVAL_SQL = """
            UPDATE collection.monitoring_profile_schedule_state
               SET interval_minutes = ?, next_due_at = ?, updated_at = ?
             WHERE monitoring_profile_id = ?
               AND interval_minutes <> ?
               AND (lease_until IS NULL OR lease_until <= ?)
            """;
    private static final String CLAIM_SQL = """
            UPDATE collection.monitoring_profile_schedule_state
               SET lease_token = ?, lease_until = ?, updated_at = ?
             WHERE monitoring_profile_id = ?
               AND interval_minutes = ?
               AND next_due_at <= ?
               AND (lease_until IS NULL OR lease_until <= ?)
            RETURNING monitoring_profile_id
            """;
    private static final String RENEW_SQL = """
            UPDATE collection.monitoring_profile_schedule_state
               SET lease_until = ?, updated_at = ?
             WHERE monitoring_profile_id = ? AND lease_token = ?
            """;
    private static final String COMPLETE_SQL = """
            UPDATE collection.monitoring_profile_schedule_state
               SET lease_token = NULL,
                   lease_until = NULL,
                   next_due_at = ?,
                   updated_at = ?
             WHERE monitoring_profile_id = ? AND lease_token = ?
            """;

    private final Connection connection;

    public JdbcProfileScheduleStateStore(@Named("default") Connection connection) {
        this.connection = connection;
    }

    @Override
    public Optional<ProfileScheduleLease> claimIfDue(
            MonitoringProfileId profileId,
            int intervalMinutes,
            Instant now,
            Duration leaseDuration) {
        try {
            Instant nextDue = now.plus(Duration.ofMinutes(intervalMinutes));
            if (insertState(profileId, intervalMinutes, nextDue, now)) {
                return Optional.empty();
            }
            if (rescheduleChangedInterval(profileId, intervalMinutes, nextDue, now)) {
                return Optional.empty();
            }

            UUID token = UUID.randomUUID();
            Instant leaseUntil = now.plus(leaseDuration);
            try (PreparedStatement statement = connection.prepareStatement(CLAIM_SQL)) {
                statement.setObject(1, token);
                statement.setTimestamp(2, Timestamp.from(leaseUntil));
                statement.setTimestamp(3, Timestamp.from(now));
                statement.setObject(4, profileId.value());
                statement.setInt(5, intervalMinutes);
                statement.setTimestamp(6, Timestamp.from(now));
                statement.setTimestamp(7, Timestamp.from(now));
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next()
                            ? Optional.of(new ProfileScheduleLease(profileId, token, intervalMinutes))
                            : Optional.empty();
                }
            }
        } catch (SQLException | RuntimeException exception) {
            throw failure("Failed to claim monitoring-profile schedule", exception);
        }
    }

    @Override
    public boolean renew(ProfileScheduleLease lease, Instant now, Duration leaseDuration) {
        try (PreparedStatement statement = connection.prepareStatement(RENEW_SQL)) {
            statement.setTimestamp(1, Timestamp.from(now.plus(leaseDuration)));
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setObject(3, lease.profileId().value());
            statement.setObject(4, lease.token());
            return statement.executeUpdate() == 1;
        } catch (SQLException | RuntimeException exception) {
            throw failure("Failed to renew monitoring-profile schedule lease", exception);
        }
    }

    @Override
    public boolean complete(ProfileScheduleLease lease, Instant completedAt) {
        try (PreparedStatement statement = connection.prepareStatement(COMPLETE_SQL)) {
            statement.setTimestamp(1, Timestamp.from(completedAt.plus(Duration.ofMinutes(lease.intervalMinutes()))));
            statement.setTimestamp(2, Timestamp.from(completedAt));
            statement.setObject(3, lease.profileId().value());
            statement.setObject(4, lease.token());
            return statement.executeUpdate() == 1;
        } catch (SQLException | RuntimeException exception) {
            throw failure("Failed to complete monitoring-profile schedule lease", exception);
        }
    }

    private boolean insertState(
            MonitoringProfileId profileId, int intervalMinutes, Instant nextDue, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_STATE_SQL)) {
            statement.setObject(1, profileId.value());
            statement.setInt(2, intervalMinutes);
            statement.setTimestamp(3, Timestamp.from(nextDue));
            statement.setTimestamp(4, Timestamp.from(now));
            return statement.executeUpdate() == 1;
        }
    }

    private boolean rescheduleChangedInterval(
            MonitoringProfileId profileId, int intervalMinutes, Instant nextDue, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RESCHEDULE_INTERVAL_SQL)) {
            statement.setInt(1, intervalMinutes);
            statement.setTimestamp(2, Timestamp.from(nextDue));
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setObject(4, profileId.value());
            statement.setInt(5, intervalMinutes);
            statement.setTimestamp(6, Timestamp.from(now));
            return statement.executeUpdate() == 1;
        }
    }

    private static ProfileSchedulePersistenceException failure(String message, Throwable cause) {
        return cause instanceof ProfileSchedulePersistenceException persistenceException
                ? persistenceException
                : new ProfileSchedulePersistenceException(message, cause);
    }
}
